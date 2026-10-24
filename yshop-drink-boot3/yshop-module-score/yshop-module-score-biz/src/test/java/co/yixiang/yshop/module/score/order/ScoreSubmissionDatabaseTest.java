package co.yixiang.yshop.module.score.order;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import co.yixiang.yshop.framework.common.exception.ServiceException;
import java.util.*;

/** Replays must preserve exactly one committed financial movement. */
class ScoreSubmissionDatabaseTest {
    ScoreOrderDatabaseFixture f;
    @BeforeEach void setup() throws Exception { f=new ScoreOrderDatabaseFixture(); f.setup(); }
    @AfterEach void close() throws Exception { if(f!=null)f.close(); }
    @Test void sameIntentRepeatedReturnsOriginalSuccessWithoutSecondMovement() {
        var request=f.request("1");
        assertDoesNotThrow(()->f.orders.submit(1L,request), "First valid intent must succeed");
        assertDoesNotThrow(()->f.orders.submit(1L,request), "Committed key must replay successfully");
        assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_order"));
        assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_user_bill"));
        f.decimal("900","SELECT integral FROM yshop_user WHERE id=1");
        assertEquals(9,f.n("SELECT stock FROM yshop_score_product"));
        assertEquals(1,f.n("SELECT sales FROM yshop_score_product"));
    }

    @Test void normalizedPayloadReplaysSameCommittedResult() {
        var p=f.request("01");p.setProductId("001");p.setAddressId("001");f.orders.submit(1L,p);
        p.setNum("1");p.setProductId("1");p.setAddressId("1");f.orders.submit(1L,p);once();
    }
    @RepeatedTest(5) void simultaneousSameKeyCommitsExactlyOneMovement() throws Exception {
        var p=f.request("1");assertTrue(f.race(i->{f.orders.submit(1L,p);return true;}).stream().allMatch(Boolean::booleanValue));once();
    }
    @ParameterizedTest @ValueSource(strings={"product","quantity","address"})
    void sameKeyDifferentPayloadConflictsWithoutFurtherMovement(String field) {
        var p=f.request("1");f.orders.submit(1L,p);
        switch(field){case "product"->p.setProductId("2");case "quantity"->p.setNum("2");default->p.setAddressId("2");}
        var error=assertThrows(ServiceException.class,()->f.orders.submit(1L,p));assertEquals(1008018005,error.getCode());once();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"short","123456789012345!","123456789012345 ","123456789012345中","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void missingOrInvalidKeyAsksClientToUpdateWithoutWrites(String key) {
        var p=f.request("1");p.setIdempotencyKey(key);var error=assertThrows(ServiceException.class,()->f.orders.submit(1L,p));assertEquals(1008018004,error.getCode());assertTrue(error.getMessage().contains("更新"));f.unchanged();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"0","-1","1.5","1e3","9223372036854775808","abc"})
    void invalidAddressKeepsExistingParameterErrorWithoutClaim(String address) {
        var request=f.request("1");request.setAddressId(address);
        var error=assertThrows(ServiceException.class,()->f.orders.submit(1L,request));
        assertEquals(1004004001,error.getCode());f.unchanged();
    }
    @Test void sameKeyBelongsToEachMemberIndependently() throws Exception {
        String key="same_key_for_members";
        assertTrue(f.race(i->{var p=f.request("1");p.setIdempotencyKey(key);p.setAddressId(Integer.toString(i+1));f.orders.submit(i+1L,p);return true;}).stream().allMatch(Boolean::booleanValue));
        assertEquals(2,f.n("SELECT COUNT(*) FROM yshop_score_submission"));assertEquals(2,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(2,f.n("SELECT COUNT(*) FROM yshop_user_bill"));f.decimal("1800","SELECT SUM(integral) FROM yshop_user");assertEquals(8,f.n("SELECT stock FROM yshop_score_product"));assertEquals(2,f.n("SELECT sales FROM yshop_score_product"));
    }
    @Test void lostResponseReplaysBeforeDeletedProductAddressOrExhaustedBalanceChecks() {
        var p=f.request("1");f.orders.submit(1L,p); // The caller deliberately discards the response.
        f.db.update("UPDATE yshop_score_product SET deleted=1,is_switch=0,stock=0 WHERE id=1");
        f.db.update("UPDATE yshop_user SET integral=0,deleted=1 WHERE id=1");f.db.update("DELETE FROM yshop_user_address WHERE id=1");
        assertDoesNotThrow(()->f.orders.submit(1L,p));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_user_bill"));assertEquals(1,f.n("SELECT sales FROM yshop_score_product"));
    }
    @ParameterizedTest @ValueSource(strings={"order","stock","integral","bill"})
    void failedTransactionRemovesClaimAndSameIntentCanRetry(String window) {
        String table=switch(window){case "order"->"yshop_score_order";case "stock"->"yshop_score_product";case "integral"->"yshop_user";default->"yshop_user_bill";};
        String condition=switch(window){case "order","bill"->"number<0";case "stock"->"stock=10";default->"integral=1000";};
        f.db.execute("ALTER TABLE "+table+" ADD CONSTRAINT synthetic_retry_failure CHECK("+condition+")");var p=f.request("1");
        assertThrows(RuntimeException.class,()->f.orders.submit(1L,p));f.unchanged();
        f.db.execute("ALTER TABLE "+table+" DROP CONSTRAINT synthetic_retry_failure");f.orders.submit(1L,p);f.orders.submit(1L,p);once();
    }
    @Test void claimWriteFailureLeavesEveryBusinessResourceUntouched() {
        f.db.execute("ALTER TABLE yshop_score_submission ADD CONSTRAINT synthetic_claim_failure CHECK(uid<0)");
        assertThrows(RuntimeException.class,()->f.orders.submit(1L,f.request("1")));f.unchanged();
    }
    @Test void committedHttpResponseLossReplaysWithoutSecondMovement() throws Exception {
        var p=f.request("1");
        try(var server=new java.net.ServerSocket(0,2,java.net.InetAddress.getLoopbackAddress())) {
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var worker=pool.submit(()->{
                    for(int i=0;i<2;i++)try(var socket=server.accept()) {
                        socket.setSoTimeout(10000);
                        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));
                        int length=0;String line;while((line=reader.readLine())!=null && !line.isEmpty())if(line.startsWith("Content-Length:"))length=Integer.parseInt(line.substring(15).trim());
                        char[] body=new char[length];int read=0;while(read<length){int n=reader.read(body,read,length-read);if(n<0)throw new java.io.EOFException();read+=n;}
                        var request=f.request("1");request.setIdempotencyKey(new String(body));f.orders.submit(1L,request);
                        if(i==1){socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntrue".getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();}
                        // First committed HTTP request loses its entire response at the transport.
                    }catch(Exception error){throw new RuntimeException(error);}
                });
                for(int attempt=0;attempt<2;attempt++)try(var client=new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.getLocalPort())) {
                    client.setSoTimeout(10000);String key=p.getIdempotencyKey();
                    client.getOutputStream().write(("POST /score-order/submit HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+key.length()+"\r\n\r\n"+key).getBytes(java.nio.charset.StandardCharsets.US_ASCII));client.getOutputStream().flush();
                    String response=new String(client.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);
                    if(attempt==0)assertEquals("",response);else assertTrue(response.endsWith("true"));
                }
                worker.get(10,java.util.concurrent.TimeUnit.SECONDS);once();
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test void committedIntentReplaysAfterActualJvmRestart() throws Exception {
        var p=f.request("1");String key=p.getIdempotencyKey();
        java.nio.file.Path folder=null;
        try {
            if(f.mysql) {f.orders.submit(1L,p);}
            else {folder=java.nio.file.Files.createTempDirectory("score-restart-");child("seed",key,folder.resolve("score").toString());}
            child("replay",key,folder==null?null:folder.resolve("score").toString());
            if(f.mysql)once();
        } finally {
            if(folder!=null)try(var paths=java.nio.file.Files.walk(folder)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
        }
    }
    void child(String mode,String key,String h2) throws Exception {
        var output=java.nio.file.Files.createTempFile("score-jvm-", ".log");
        try {
            String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            var command=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,ScoreSubmissionRestartProcess.class.getName(),mode,key).redirectErrorStream(true).redirectOutput(output.toFile());
            if(h2!=null)command.environment().put("YSHOP_SCORE_ACCEPTANCE_H2",h2);
            var process=command.start();boolean ended=process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS);if(!ended)process.destroyForcibly();
            assertTrue(ended,"RESTART_PROCESS_TIMEOUT");assertEquals(0,process.exitValue(),java.nio.file.Files.readString(output));assertTrue(java.nio.file.Files.readString(output).contains("SCORE_RESTART_VERIFIED"));
        } finally {java.nio.file.Files.deleteIfExists(output);}
    }
    void once() {
        assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_submission"));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_user_bill"));f.decimal("900","SELECT integral FROM yshop_user WHERE id=1");f.decimal("900","SELECT balance FROM yshop_user_bill");f.decimal("100","SELECT number FROM yshop_user_bill");assertEquals(9,f.n("SELECT stock FROM yshop_score_product"));assertEquals(1,f.n("SELECT sales FROM yshop_score_product"));
        assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_submission s JOIN yshop_score_order o ON s.order_id=o.order_id AND s.uid=o.uid"));
    }
}
