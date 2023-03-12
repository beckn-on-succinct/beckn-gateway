package in.succinct.beckn.gateway.controller;

import com.venky.core.security.Crypt;
import com.venky.core.string.StringUtil;
import com.venky.core.util.ExceptionUtil;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.db.model.SWFHttpResponse;
import com.venky.swf.integration.FormatHelper;
import com.venky.swf.integration.IntegrationAdaptor;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.db.model.CryptoKey;
import com.venky.swf.routing.Config;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.View;
import com.vladsch.flexmark.ast.ThematicBreak;
import in.succinct.beckn.Acknowledgement;
import in.succinct.beckn.Acknowledgement.Status;
import in.succinct.beckn.City;
import in.succinct.beckn.Context;
import in.succinct.beckn.Error;
import in.succinct.beckn.Location;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.configuration.AppInstaller;
import in.succinct.beckn.gateway.extensions.BecknPublicKeyFinder;
import in.succinct.beckn.gateway.util.ECEventEmitter;
import in.succinct.beckn.gateway.util.GWConfig;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Controllers in Succinct return Views that is serialized and sent as response.
 */
public class    BgController extends Controller {
    public BgController(Path path) {
        super(path);
    }

    /**
     * Give a NACK response
     * @param request - incoming request
     * @param realm - caller
     * @return a nack response
     */
    public View nack(Request request, String realm){
        Acknowledgement nack = new Acknowledgement(Status.NACK);
        String response = new Response(new Acknowledgement(Status.NACK)).toString();
        Config.instance().getLogger(BgController.class.getName()).log(Level.WARNING,response);

        return new BytesView(getPath(),
                response.getBytes(StandardCharsets.UTF_8),
                MimeType.APPLICATION_JSON,"WWW-Authenticate","Signature realm=\""+realm+"\"",
                "headers=\"(created) (expires) digest\""){
            @Override
            public void write() throws IOException {
                super.write(HttpServletResponse.SC_UNAUTHORIZED);
            }
        };
    }

    @RequireLogin(value = false)
    public View log(long id) throws IOException{
        if (!Config.instance().isDevelopmentEnvironment()){
            throw new AccessDeniedException("You cannot view logs!");
        }
        return new BytesView(getPath(),StringUtil.readBytes(new FileInputStream(String.format("tmp/java_info0.log.%d",id)),true),MimeType.TEXT_PLAIN);
    }

    /**
     * Returns an ACK Response.
     * @param request - Incoming  Request
     * @return - ACK Response
     */
    public View ack(Request request){
        Acknowledgement ack = new Acknowledgement(Status.ACK);
        String responseString = new Response(ack).toString();
        Config.instance().getLogger(BgController.class.getName()).log(Level.WARNING,responseString);

        return new BytesView(getPath(),responseString.getBytes(StandardCharsets.UTF_8) , MimeType.APPLICATION_JSON);
    }

    /**
     * Get caller's meta information from the {@link Request#getContext()} based on which the registry will be queried
     * @param request
     * @return
     */
    private Subscriber getCriteria(Request request) {
        Subscriber criteria = new Subscriber();
        Context context = request.getContext();
        String countryCode = context.getCountry();
        String cityCode = context.getCity();
        if (countryCode != null){
            criteria.setCountry(countryCode);
        }
        if (!ObjectUtil.isVoid(cityCode)){
            if (!ObjectUtil.isVoid(context.getVersion())){
                //1.x
                criteria.setLocation(new Location());
                criteria.getLocation().setCity(new City());
                criteria.getLocation().getCity().setCode(cityCode);
            }else {
                criteria.setCity(cityCode);
            }
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
            Config.instance().getLogger(getClass().getName()).info("Headers:" + getPath().getHeaders());
            Config.instance().getLogger(getClass().getName()).info("Payload:" + request);

            /*
            Validate signature if authorization is enabled
             */
            if (!GWConfig.isAuthorizationHeaderEnabled() ||
                    request.verifySignature("Authorization",getPath().getHeaders() , GWConfig.isAuthorizationHeaderMandatory())){
                Context context = request.getContext();
                if ("search".equals(request.getContext().getAction())){
                    /* Get basic meta from context */
                    Subscriber criteria = getCriteria(request);

                    criteria.setType(Subscriber.SUBSCRIBER_TYPE_BPP);

                    if (!ObjectUtil.isVoid(context.getBppId())){
                        criteria.setSubscriberId(context.getBppId());
                    }

                    /* lookup for bpps in the domain and city/country */
                    List<Subscriber> subscriberList = BecknPublicKeyFinder.lookup(criteria);
                    for (Subscriber subscriber : subscriberList){
                        /* For each subscriber submit an async task */
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
                    /* For each subscriber submit an async task Will be only one, the BAP who fired the search*/
                    List<Subscriber> subscriberList = BecknPublicKeyFinder.lookup(criteria);
                    for (Subscriber subscriber : subscriberList){
                        tasks.add(new OnSearch(request,subscriber,getPath().getHeaders()));
                    }
                }
                //* As the tasks are not critical, these are not persisted. Non persistence also gives speed. And Persistence requires tasks to be serializable.
                TaskManager.instance().executeAsync(tasks,false); //Submit all async tasks.
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
            Config.instance().getLogger(BgController.class.getName()).log(Level.WARNING,message.toString());
            return new BytesView(getPath(),response.toString().getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
        }
    }


    /**
     * /search call is delegated to act
     * @return the ACK/NACK response
     */
    @RequireLogin(false)
    public View search() {
        return act();
    }

    /**
     * /on_search call is delegated to act
     * @return the ACK/NACK response
     */
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
            ECEventEmitter ecEventEmitter = new ECEventEmitter();
            if (ecEventEmitter.isEventPublishingRequired(originalRequest)){
                try{
                    long maxSleepTime = 2*1000L; //2 seconds
                    Thread.sleep(Math.max(Config.instance().getLongProperty("ec.sleep.time.millis",maxSleepTime),maxSleepTime));
                }catch (Exception ex){
                    //
                }
            }
            Request clone = new Request(originalRequest.toString());

            Call<InputStream> call = new Call<InputStream>().url(bpp.getSubscriberUrl()+ "/"+clone.getContext().getAction()).
                    method(HttpMethod.POST).inputFormat(InputFormat.INPUT_STREAM).timeOut(GWConfig.getTimeOut()).
                    input(new ByteArrayInputStream(clone.toString().getBytes(StandardCharsets.UTF_8))).headers(getHeaders(clone));

            if (headers != null && headers.containsKey("Authorization")){
                call.header("Authorization",headers.get("Authorization"));
            }
            try {
                call.getResponseAsJson();
            }catch (RuntimeException ex){
                if (ExceptionUtil.getEmbeddedException(ex,HttpTimeoutException.class) != null && GWConfig.disableSlowBpp()){
                    new Call<JSONObject>().method(HttpMethod.POST).url(GWConfig.getRegistryUrl() +"/subscribers/disable").
                            input(bpp.getInner()).inputFormat(InputFormat.JSON).headers(getHeaders(clone))
                            .header("content-type", MimeType.APPLICATION_JSON.toString())
                            .header("accept",MimeType.APPLICATION_JSON.toString()).hasErrors();
                }
            }
            ecEventEmitter.emit(bpp,clone);
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
            ECEventEmitter ecEventEmitter = new ECEventEmitter();
            if (ecEventEmitter.isEventPublishingRequired(originalRequest)){
                try{
                    long maxSleepTime = 2*1000L; //2 seconds
                    Thread.sleep(Math.max(Config.instance().getLongProperty("bg.ec.sleep.time.millis",maxSleepTime),maxSleepTime));
                }catch (Exception ex){
                    //
                }
            }
            Request clone = new Request(originalRequest.toString());

            Call<InputStream> call = new Call<InputStream>().url(bap.getSubscriberUrl()+ "/"+clone.getContext().getAction()).
                    method(HttpMethod.POST).inputFormat(InputFormat.INPUT_STREAM).timeOut(GWConfig.getTimeOut()). //5 Seconds
                    input(new ByteArrayInputStream(clone.toString().getBytes(StandardCharsets.UTF_8))).headers(getHeaders(clone));
            if (this.headers != null && headers.containsKey("Authorization")){
                call.header("Authorization",headers.get("Authorization"));
            }
            new ECEventEmitter().emit(bap,clone);
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

    public View subscribe() {
        AppInstaller.registerBecknKeys();
        return IntegrationAdaptor.instance(SWFHttpResponse.class, FormatHelper.getFormatClass(MimeType.APPLICATION_JSON)).createStatusResponse(getPath(),null);
    }
}
