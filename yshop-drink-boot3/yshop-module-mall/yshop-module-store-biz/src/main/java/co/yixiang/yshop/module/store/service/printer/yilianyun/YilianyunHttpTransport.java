package co.yixiang.yshop.module.store.service.printer.yilianyun;

import co.yixiang.yshop.module.store.service.printer.PrinterFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class YilianyunHttpTransport implements YilianyunTransport {
    private final URI base;
    private final HttpClient client;
    private final Duration timeout;
    public YilianyunHttpTransport() { this(URI.create("https://open-api.10ss.net/v2")); }
    YilianyunHttpTransport(URI base) { this(base,Duration.ofSeconds(10)); }
    YilianyunHttpTransport(URI base,Duration timeout) {
        this.timeout=timeout; this.base=base; client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public JsonNode post(String path,Map<String,String> form) {
        boolean mutation=!path.equals("/oauth/oauth")&&!path.equals("/printer/getprintstatus");
        String body=form.entrySet().stream().map(entry->encode(entry.getKey())+"="+encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(base+path)).timeout(timeout)
                    .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            // Bound response size; never put raw responses into exceptions or logs.
            HttpResponse<byte[]> response=send(request);
            byte[] bytes=response.body();
            if(response.statusCode()!=200) throw new PrinterFailure("PROVIDER_HTTP_ERROR",mutation);
            if(bytes.length>65536) throw new PrinterFailure("PROVIDER_INVALID_RESPONSE",mutation);
            JsonNode result=new ObjectMapper().readTree(bytes);
            if(result==null||!result.path("error").isIntegralNumber())
                throw new PrinterFailure("PROVIDER_INVALID_RESPONSE",mutation);
            if(result.path("error").asInt()!=0)
                throw new PrinterFailure("PROVIDER_REJECTED_"+result.path("error").asInt(),false);
            return result.path("body");
        } catch(PrinterFailure ex) { throw ex; }
        catch(TimeoutException ex) { throw new PrinterFailure("PROVIDER_TIMEOUT",mutation); }
        catch(ExecutionException ex) { throw new PrinterFailure(ex.getCause() instanceof HttpTimeoutException?"PROVIDER_TIMEOUT":"PROVIDER_UNAVAILABLE",mutation); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new PrinterFailure("PROVIDER_TIMEOUT",mutation); }
        catch(Exception ex) { throw new PrinterFailure("PROVIDER_UNAVAILABLE",mutation); }
    }
    private HttpResponse<byte[]> send(HttpRequest request) throws InterruptedException,ExecutionException,TimeoutException {
        var pending=client.sendAsync(request,info->new LimitedBody());
        try { return pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(InterruptedException|TimeoutException ex) { pending.cancel(true); throw ex; }
    }
    private String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) bytes+=buffer.remaining();
            if(bytes>65536) { subscription.cancel(); delegate.onError(new java.io.IOException("RESPONSE_TOO_LARGE")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
