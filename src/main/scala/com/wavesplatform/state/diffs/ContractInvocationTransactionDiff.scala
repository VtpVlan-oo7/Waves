package com.wavesplatform.state.diffs

import cats.implicits._
import cats.kernel.Monoid
import com.google.common.base.Throwables
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.lang.contract.Contract
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.lang.v1.evaluator.ctx.impl.waves.WavesContext
import com.wavesplatform.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.wavesplatform.lang.v1.evaluator.{ContractEvaluator, ContractResult}
import com.wavesplatform.lang.v1.traits.domain.Tx.ContractTransfer
import com.wavesplatform.lang.v1.traits.domain.{DataItem, Recipient}
import com.wavesplatform.lang.{Global, StdLibVersion}
import com.wavesplatform.state._
import com.wavesplatform.state.reader.CompositeBlockchain
import com.wavesplatform.transaction.ValidationError
import com.wavesplatform.transaction.ValidationError._
import com.wavesplatform.transaction.smart.BlockchainContext.In
import com.wavesplatform.transaction.smart.script.ContractScript.ContractScriptImpl
import com.wavesplatform.transaction.smart.script.ScriptRunner.TxOrd
import com.wavesplatform.transaction.smart.script.{Script, ScriptRunner}
import com.wavesplatform.transaction.smart.{ContractInvocationTransaction, WavesEnvironment}
import monix.eval.Coeval
import shapeless.Coproduct

import scala.util.{Failure, Success, Try}

object ContractInvocationTransactionDiff {
  def apply(blockchain: Blockchain, height: Int)(tx: ContractInvocationTransaction): Either[ValidationError, Diff] = {
    val sc = blockchain.accountScript(tx.contractAddress)

    def evalContract(contract: Contract) = {
      val ctx = Monoid
        .combineAll(
          Seq(
            PureContext.build(StdLibVersion.V3),
            CryptoContext.build(Global),
            WavesContext.build(StdLibVersion.V3,
                               new WavesEnvironment(AddressScheme.current.chainId, Coeval(tx.asInstanceOf[In]), Coeval(height), blockchain),
                               false)
          ))
        .evaluationContext

      val invoker                                       = tx.sender.toAddress.bytes
      val maybePayment: Option[(Long, Option[ByteStr])] = tx.payment.map(p => (p.amount, p.assetId))
      ContractEvaluator.apply(ctx, contract, ContractEvaluator.Invocation(tx.fc, invoker, maybePayment, tx.contractAddress.bytes))
    }

    sc match {
      case Some(ContractScriptImpl(_, contract, _)) =>
        val functionName = tx.fc.function.asInstanceOf[FunctionHeader.User].name
        contract.cfs.find(_.u.name == functionName) match {
          case None => Left(GenericError(s"No function '$functionName' at address ${tx.contractAddress}"))
          case Some(_) =>
            evalContract(contract).left
              .map(a => GenericError(a.toString): ValidationError)
              .flatMap {
                case ContractResult(ds, ps) =>
                  import cats.implicits._

                  val dataAndPaymentDiff = payableAndDataPart(height, tx, ds)
                  val pmts: List[Map[Address, Map[Option[ByteStr], Long]]] = ps.map {
                    case (Recipient.Address(addrBytes), amt, maybeAsset) =>
                      Map(Address.fromBytes(addrBytes.arr).explicitGet() -> Map(maybeAsset -> amt))
                  }
                  for {
                    _ <- Either.cond(pmts.flatMap(_.values).flatMap(_.values).forall(_ >= 0), (), ValidationError.NegativeAmount(-42, ""))
                    _ <- Either.cond(
                      pmts
                        .flatMap(_.values)
                        .flatMap(_.keys)
                        .flatten
                        .forall(blockchain.assetDescription(_).isDefined),
                      (),
                      GenericError(s"Unissued assets are not allowed")
                    )
                    _ <- Either.cond(true, (), ValidationError.NegativeAmount(-42, "")) //  - sum doesn't overflow
                    _ <- Either.cond(true, (), ValidationError.NegativeAmount(-42, "")) //  - whatever else tranfser/massTransfer ensures
                    _ <- foldContractTransfers(blockchain, tx)(ps, dataAndPaymentDiff)
                  } yield {
                    val paymentReceiversMap: Map[Address, Portfolio] = Monoid
                      .combineAll(pmts)
                      .mapValues(mp => mp.toList.map(x => Portfolio.build(x._1, x._2)))
                      .mapValues(l => Monoid.combineAll(l))
                    val paymentFromContractMap = Map(tx.contractAddress -> Monoid.combineAll(paymentReceiversMap.values).negate)
                    val transfers              = Monoid.combineAll(Seq(paymentReceiversMap, paymentFromContractMap))
                    dataAndPaymentDiff.combine(Diff.stateOps(portfolios = transfers))
                  }
              }
        }
      case _ => Left(GenericError(s"No contract at address ${tx.contractAddress}"))
    }

  }

  private def payableAndDataPart(height: Int, tx: ContractInvocationTransaction, ds: List[DataItem[_]]) = {
    val r: Seq[DataEntry[_]] = ds.map {
      case DataItem.Bool(k, b) => BooleanDataEntry(k, b)
      case DataItem.Str(k, b)  => StringDataEntry(k, b)
      case DataItem.Lng(k, b)  => IntegerDataEntry(k, b)
      case DataItem.Bin(k, b)  => BinaryDataEntry(k, b)
    }
    val payablePart: Map[Address, Portfolio] = tx.payment match {
      case None => Map.empty
      case Some(ContractInvocationTransaction.Payment(amt, assetOpt)) =>
        assetOpt match {
          case Some(asset) =>
            Map(tx.sender.toAddress -> Portfolio(0, LeaseBalance.empty, Map(asset -> -amt))).combine(
              Map(tx.contractAddress -> Portfolio(0, LeaseBalance.empty, Map(asset -> amt)))
            )
          case None =>
            Map(tx.sender.toAddress -> Portfolio(-amt, LeaseBalance.empty, Map.empty))
              .combine(Map(tx.contractAddress -> Portfolio(amt, LeaseBalance.empty, Map.empty)))
        }
    }
    val feePart = Map(tx.sender.toAddress -> Portfolio(-tx.fee, LeaseBalance.empty, Map.empty))
    Diff(
      height = height,
      tx = tx,
      portfolios = feePart combine payablePart,
      accountData = Map(tx.contractAddress -> AccountDataInfo(r.map(d => d.key -> d).toMap))
    )

  }
  private def foldContractTransfers(blockchain: Blockchain, tx: ContractInvocationTransaction)(ps: List[(Recipient.Address, Long, Option[ByteStr])],
                                                                                               dataDiff: Diff): Either[ValidationError, Diff] = {

    ps.foldLeft(Either.right[ValidationError, Diff](dataDiff)) { (diffEi, payment) =>
      val (addressRepr, amount, asset) = payment
      val address                      = Address.fromBytes(addressRepr.bytes.arr).explicitGet()
      asset match {
        case None =>
          diffEi combine Right(
            Diff.stateOps(
              portfolios = Map(
                address            -> Portfolio(amount, LeaseBalance.empty, Map.empty),
                tx.contractAddress -> Portfolio(-amount, LeaseBalance.empty, Map.empty)
              )))
        case Some(assetId) =>
          diffEi combine {
            val nextDiff = Diff.stateOps(
              portfolios = Map(
                address            -> Portfolio(0, LeaseBalance.empty, Map(assetId -> amount)),
                tx.contractAddress -> Portfolio(0, LeaseBalance.empty, Map(assetId -> -amount))
              ))
            blockchain.assetScript(assetId) match {
              case None =>
                Right(nextDiff)
              case Some(script) =>
                diffEi flatMap (d => validateContractTransferWithSmartAssetScript(blockchain, tx)(d, addressRepr, amount, asset, nextDiff, script))
            }
          }
      }
    }
  }

  private def validateContractTransferWithSmartAssetScript(blockchain: Blockchain, tx: ContractInvocationTransaction)(
      totalDiff: Diff,
      addressRepr: Recipient.Address,
      amount: Long,
      asset: Option[ByteStr],
      nextDiff: Diff,
      script: Script): Either[ValidationError, Diff] = {
    Try {
      ScriptRunner(
        blockchain.height,
        Coproduct[TxOrd](
          ContractTransfer(
            asset,
            Recipient.Address(tx.contractAddress.bytes),
            Recipient.Address(addressRepr.bytes),
            amount,
            tx.timestamp,
            tx.id()
          )),
        CompositeBlockchain.composite(blockchain, totalDiff),
        script,
        true
      ) match {
        case (log, Left(execError)) => Left(ScriptExecutionError(execError, log, true))
        case (log, Right(FALSE)) =>
          Left(TransactionNotAllowedByScript(log, isTokenScript = true))
        case (_, Right(TRUE)) => Right(nextDiff)
        case (_, Right(x))    => Left(GenericError(s"Script returned not a boolean result, but $x"))
      }
    } match {
      case Failure(e) =>
        Left(ScriptExecutionError(s"Uncaught execution error: ${Throwables.getStackTraceAsString(e)}", List.empty, true))
      case Success(s) => s

    }
  }
}
