package in.succinct.beckn.gateway.controller;

import com.venky.core.security.Crypt;
import com.venky.core.string.StringUtil;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.plugins.collab.db.model.CryptoKey;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.View;
import in.succinct.beckn.Acknowledgement;
import in.succinct.beckn.Acknowledgement.Status;
import in.succinct.beckn.Context;
import in.succinct.beckn.Error;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.extensions.BecknPublicKeyFinder;
import in.succinct.beckn.gateway.util.GWConfig;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BgController extends Controller {
    public BgController(Path path) {
        super(path);
    }
    public View nack(Request request, String realm){
        Acknowledgement nack = new Acknowledgement(Status.NACK);
        return new BytesView(getPath(),
                new Response(request.getContext(),new Acknowledgement(Status.NACK)).toString().getBytes(StandardCharsets.UTF_8),
                MimeType.APPLICATION_JSON,"WWW-Authenticate","Signature realm=\""+realm+"\"",
                "headers=\"(created) (expires) digest\""){
            @Override
            public void write() throws IOException {
                super.write(HttpServletResponse.SC_UNAUTHORIZED);
            }
        };
    }
    public View ack(Request request){
        Acknowledgement ack = new Acknowledgement(Status.ACK);
        return new BytesView(getPath(),new Response(request.getContext(),ack).toString().getBytes(StandardCharsets.UTF_8) , MimeType.APPLICATION_JSON);
    }

    private Subscriber getCriteria(Request request) {
        Subscriber criteria = new Subscriber();
        Context context = request.getContext();
        String countryCode = context.get("country");
        String cityCode = context.get("city");
        if (countryCode != null){
            criteria.setCountry(countryCode);
        }
        if (!ObjectUtil.isVoid(cityCode)){
            criteria.setCity(cityCode);
        }
        criteria.setDomain(context.getDomain());
        criteria.setStatus(Subscriber.SUBSCRIBER_STATUS_SUBSCRIBED);

        return criteria;
    }
    private View act(){
        Request request = null;
        try {
            request = new Request(StringUtil.read(getPath().getInputStream()));
            List<Task> tasks = new ArrayList<>();
            if (!GWConfig.isAuthorizationHeaderEnabled() ||
                    request.verifySignature("Authorization",getPath().getHeaders() , false)){
                Context context = request.getContext();
                if ("search".equals(request.getContext().getAction())){
                    Subscriber criteria = getCriteria(request);
                    criteria.setType(Subscriber.SUBSCRIBER_TYPE_BPP);

                    if (!ObjectUtil.isVoid(context.getBppId())){
                        criteria.setSubscriberId(context.getBppId());
                    }

                    List<Subscriber> subscriberList = BecknPublicKeyFinder.lookup(criteria);
                    for (Subscriber subscriber : subscriberList){
                        tasks.add(new Search(request,subscriber,getPath().getHeaders()));
                    }
                }else if ("on_search".equals(request.getContext().getAction())){
                    Subscriber criteria = getCriteria(request);
                    criteria.setType(Subscriber.SUBSCRIBER_TYPE_BAP);
                    if (!ObjectUtil.isVoid(context.getBapId())){
                        criteria.setSubscriberId(context.getBapId());
                    }else  {
                        throw new RuntimeException("BAP not known!");
                    }
                    List<Subscriber> subscriberList = BecknPublicKeyFinder.lookup(criteria);
                    for (Subscriber subscriber : subscriberList){
                        tasks.add(new OnSearch(request,subscriber,getPath().getHeaders()));
                    }
                }
                TaskManager.instance().executeAsync(tasks,false);
                return ack(request);
            }else {
                return nack(request,request.getContext().getBapId());
            }
        }catch (Exception ex){
            if (request == null){
                throw new RuntimeException();
            }
            Request response  = new Request();
            Error error = new Error();
            response.setContext(request.getContext());
            response.setError(error);
            error.setCode(ex.getMessage());
            StringWriter message = new StringWriter();
            ex.printStackTrace(new PrintWriter(message));
            error.setMessage(message.toString());
            return new BytesView(getPath(),response.toString().getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
        }
    }


    @RequireLogin(false)
    public View search() {
        return act();
    }

    @RequireLogin(false)
    public View on_search() {
        return act();
    }
    protected static Map<String, String> getHeaders(Request request) {
        Map<String,String> headers  = new HashMap<>();
        if (GWConfig.isAuthorizationHeaderEnabled()) {

            String subscriberId = GWConfig.getSubscriberId();
            String pub_key_id = GWConfig.getPublicKeyId();
            String authHeader = request.generateAuthorizationHeader(subscriberId,pub_key_id);
            headers.put("X-Gateway-Authorization", authHeader);
            headers.put("Proxy-Authorization", authHeader);

        }
        headers.put("Content-Type", MimeType.APPLICATION_JSON.toString());
        headers.put("Accept", MimeType.APPLICATION_JSON.toString());

        return headers;
    }



    public static class Search implements Task {
        Request originalRequest;
        Subscriber bpp ;
        Map<String,String> headers;
        public Search(Request request, Subscriber bpp, Map<String, String> headers){
            this.originalRequest = request;
            this.bpp = bpp;
            this.headers = headers;
        }

        @Override
        public void execute() {
            Request clone = new Request(originalRequest.toString());

            Call<InputStream> call = new Call<InputStream>().url(bpp.getSubscriberUrl()+ "/"+clone.getContext().getAction()).
                    method(HttpMethod.POST).inputFormat(InputFormat.INPUT_STREAM).
                    input(new ByteArrayInputStream(clone.toString().getBytes(StandardCharsets.UTF_8))).headers(getHeaders(clone));

            if (headers != null && headers.containsKey("Authorization")){
                call.header("Authorization",headers.get("Authorization"));
            }
            call.getResponseAsJson();
        }
    }
    public static class OnSearch implements Task {
        Request originalRequest;
        Subscriber bap ;
        Map<String,String> headers;
        public OnSearch(Request request, Subscriber bap, Map<String, String> headers){
            this.originalRequest = request;
            this.bap = bap;
            this.headers = headers;
        }
        @Override
        public void execute() {
            Request clone = new Request(originalRequest.toString());

            Call<InputStream> call = new Call<InputStream>().url(bap.getSubscriberUrl()+ "/"+clone.getContext().getAction()).
                    method(HttpMethod.POST).inputFormat(InputFormat.INPUT_STREAM).
                    input(new ByteArrayInputStream(clone.toString().getBytes(StandardCharsets.UTF_8))).headers(getHeaders(clone));
            if (this.headers != null && headers.containsKey("Authorization")){
                call.header("Authorization",headers.get("Authorization"));
            }
            call.getResponseAsJson();
        }
    }


    @RequireLogin(value = false)
    @SuppressWarnings("unchecked")
    public View on_subscribe() throws Exception{
        String payload = StringUtil.read(getPath().getInputStream());
        JSONObject object = (JSONObject) JSONValue.parse(payload);


        if (!Request.verifySignature(getPath().getHeader("Signature"), payload, GWConfig.getRegistrySigningPublicKey())){
            throw new RuntimeException("Cannot verify Signature");
        }

        PrivateKey privateKey = Crypt.getInstance().getPrivateKey(Request.ENCRYPTION_ALGO,
                CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPrivateKey());

        PublicKey registryPublicKey = Request.getEncryptionPublicKey(GWConfig.getRegistryEncryptionPublicKey());

        KeyAgreement agreement = KeyAgreement.getInstance(Request.ENCRYPTION_ALGO);
        agreement.init(privateKey);
        agreement.doPhase(registryPublicKey,true);

        SecretKey key = agreement.generateSecret("TlsPremasterSecret");

        JSONObject output = new JSONObject();
        output.put("answer", Crypt.getInstance().decrypt((String)object.get("challenge"),"AES",key));

        return new BytesView(getPath(),output.toString().getBytes(),MimeType.APPLICATION_JSON);
    }
}
