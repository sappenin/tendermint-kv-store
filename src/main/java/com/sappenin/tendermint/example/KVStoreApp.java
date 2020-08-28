package com.sappenin.tendermint.example;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import jetbrains.exodus.ArrayByteIterable;
import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.env.Environment;
import jetbrains.exodus.env.Store;
import jetbrains.exodus.env.StoreConfig;
import jetbrains.exodus.env.Transaction;
import tendermint.abci.ABCIApplicationGrpc.ABCIApplicationImplBase;
import tendermint.abci.Types.RequestBeginBlock;
import tendermint.abci.Types.RequestCheckTx;
import tendermint.abci.Types.RequestCommit;
import tendermint.abci.Types.RequestDeliverTx;
import tendermint.abci.Types.RequestEcho;
import tendermint.abci.Types.RequestEndBlock;
import tendermint.abci.Types.RequestInfo;
import tendermint.abci.Types.RequestInitChain;
import tendermint.abci.Types.RequestQuery;
import tendermint.abci.Types.ResponseBeginBlock;
import tendermint.abci.Types.ResponseCheckTx;
import tendermint.abci.Types.ResponseCommit;
import tendermint.abci.Types.ResponseDeliverTx;
import tendermint.abci.Types.ResponseEcho;
import tendermint.abci.Types.ResponseEndBlock;
import tendermint.abci.Types.ResponseInfo;
import tendermint.abci.Types.ResponseInitChain;
import tendermint.abci.Types.ResponseQuery;
import tendermint.abci.Types.ResponseQuery.Builder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A demonstration of how to connect a Java application to a Tendermint blockchain using ABCI.
 *
 * @see "https://docs.tendermint.com/master/tutorials/java.html"
 */
public class KVStoreApp extends ABCIApplicationImplBase {

  private Environment env;
  private Transaction txn = null;
  private Store store = null;

  public KVStoreApp(Environment env) {
    this.env = env;
  }

  /**
   * When a new transaction is added to the Tendermint Core, it will ask the application to check it (validate the
   * format, signatures, etc.).
   *
   * If the transaction does not have a form of {bytes}={bytes}, we return 1 code. When the same key=value already exist
   * (same key and value), we return 2 code. For others, we return a zero code indicating that they are valid.
   *
   * Note that anything with non-zero code will be considered invalid (-1, 100, etc.) by Tendermint Core.
   *
   * @param req
   * @param responseObserver
   */
  @Override
  public void checkTx(RequestCheckTx req, StreamObserver<ResponseCheckTx> responseObserver) {
    ByteString tx = req.getTx();
    int code = validate(tx);
    ResponseCheckTx resp = ResponseCheckTx.newBuilder()
      .setCode(code)
      .setGasWanted(1)
      .build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  /**
   * Validate a transaction.
   *
   * @param tx A {@link ByteString} containing information about a transaction in Tendermint Core.
   *
   * @return
   */
  @VisibleForTesting
  protected int validate(ByteString tx) {
    List<byte[]> parts = split(tx, '=');
    if (parts.size() != 2) {
      return 1;
    }
    byte[] key = parts.get(0);
    byte[] value = parts.get(1);

    // check if the same key=value already exists
    byte[] stored = getPersistedValue(key);
    if (Arrays.equals(stored, value)) {
      return 2;
    }

    return 0;
  }

  @VisibleForTesting
  protected List<byte[]> split(ByteString tx, char separator) {
    byte[] arr = tx.toByteArray();
    int i;
    for (i = 0; i < tx.size(); i++) {
      if (arr[i] == (byte) separator) {
        break;
      }
    }
    if (i == tx.size()) {
      return Collections.emptyList();
    }
    return Lists.newArrayList(
      tx.substring(0, i).toByteArray(),
      tx.substring(i + 1).toByteArray()
    );
  }

  private byte[] getPersistedValue(byte[] k) {
    return env.computeInReadonlyTransaction(txn -> {
      Store store = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txn);
      ByteIterable byteIterable = store.get(txn, new ArrayByteIterable(k));
      if (byteIterable == null) {
        return null;
      }
      return byteIterable.getBytesUnsafe();
    });
  }


  /**
   * When Tendermint Core has decided on the block, it's transferred to the application in 3 parts: BeginBlock, one
   * DeliverTx per transaction and EndBlock in the end. DeliverTx are being transferred asynchronously, but the
   * responses are expected to come in order.
   *
   * @param req
   * @param responseObserver
   */
  @Override
  public void beginBlock(RequestBeginBlock req, StreamObserver<ResponseBeginBlock> responseObserver) {
    txn = env.beginTransaction();
    store = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txn);
    ResponseBeginBlock resp = ResponseBeginBlock.newBuilder().build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  /**
   * Here we begin a new transaction, which will accumulate the block's transactions and open the corresponding store.
   *
   * If the transaction is badly formatted or the same key=value already exist, we again return the non-zero code.
   * Otherwise, we add it to the store.
   *
   * In the current design, a block can include incorrect transactions (those who passed CheckTx, but failed DeliverTx
   * or transactions included by the proposer directly). This is done for performance reasons.
   *
   * @param req
   * @param responseObserver
   */
  @Override
  public void deliverTx(RequestDeliverTx req, StreamObserver<ResponseDeliverTx> responseObserver) {
    ByteString tx = req.getTx();
    int code = validate(tx);

    // If the transaction is badly formatted or the same key=value already exist, we again return the non-zero code.
    // Otherwise, we add it to the store.
    if (code == 0) {
      List<byte[]> parts = split(tx, '=');
      ArrayByteIterable key = new ArrayByteIterable(parts.get(0));
      ArrayByteIterable value = new ArrayByteIterable(parts.get(1));
      store.put(txn, key, value);
    }
    ResponseDeliverTx resp = ResponseDeliverTx.newBuilder()
      .setCode(code)
      .build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  /**
   * Commit instructs the application to persist the new state.
   *
   * Note we can't commit transactions inside the DeliverTx because in such case Query, which may be called in parallel,
   * will return inconsistent data (i.e. it will report that some value already exist even when the actual block was not
   * yet committed).
   *
   * @param req
   * @param responseObserver
   */
  @Override
  public void commit(RequestCommit req, StreamObserver<ResponseCommit> responseObserver) {
    txn.commit();
    ResponseCommit resp = ResponseCommit.newBuilder()
      .setData(ByteString.copyFrom(new byte[8]))
      .build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  /**
   * Now, when the client wants to know whenever a particular key/value exist, it will call Tendermint Core RPC
   * /abci_query endpoint, which in turn will call the application's Query method.
   *
   * Applications are free to provide their own APIs. But by using Tendermint Core as a proxy, clients (including light
   * client package) can leverage the unified API across different applications. Plus they won't have to call the
   * otherwise separate Tendermint Core API for additional proofs.
   *
   * @param req
   * @param responseObserver
   */
  @Override
  public void query(RequestQuery req, StreamObserver<ResponseQuery> responseObserver) {
    byte[] k = req.getData().toByteArray();
    byte[] v = getPersistedValue(k);
    Builder builder = ResponseQuery.newBuilder();
    if (v == null) {
      builder.setLog("does not exist");
    } else {
      builder.setLog("exists");
      builder.setKey(ByteString.copyFrom(k));
      builder.setValue(ByteString.copyFrom(v));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  // Required for tendermint core to startup
  @Override
  public void echo(RequestEcho request, StreamObserver<ResponseEcho> responseObserver) {
    //super.echo(request, responseObserver);
    responseObserver.onNext(ResponseEcho.newBuilder().setMessage("Java app is alive!").build());
    responseObserver.onCompleted();
  }

  // Required for tendermint core to startup
  @Override
  public void info(RequestInfo request, StreamObserver<ResponseInfo> responseObserver) {
    responseObserver.onNext(ResponseInfo.newBuilder().setAppVersion(1).setData("Java").build());
    responseObserver.onCompleted();
  }

  @Override
  public void initChain(RequestInitChain request, StreamObserver<ResponseInitChain> responseObserver) {
    responseObserver.onNext(ResponseInitChain.newBuilder().build());
    responseObserver.onCompleted();
  }

  /**
   * @param request
   * @param responseObserver
   */
  @Override
  public void endBlock(RequestEndBlock request, StreamObserver<ResponseEndBlock> responseObserver) {
    responseObserver.onNext(ResponseEndBlock.newBuilder().build());
    responseObserver.onCompleted();
  }
}
