package in.succinct.beckn.gateway.configuration;

import com.venky.core.security.Crypt;
import com.venky.swf.configuration.Installer;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.db.model.CryptoKey;
import com.venky.swf.routing.Config;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.extensions.BecknPublicKeyFinder;
import in.succinct.beckn.gateway.util.GWConfig;
import org.json.simple.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

public class AppInstaller implements Installer {

    /*
    Succinct framework calls this function in each module's installer.
     swf.properties in each module's(resources/config), tells the framework of this and other information.

     */
    public void install() {
        generatePrivateKeys();
        registerBecknKeys(); //Will fail unless registry has the required keys.
    }

    /* Generate the Private Keys needed to communicate with beckn network
    This data is stored in a table called CRYPTO_KEYS , you will be able to access it  as "/crypto_keys",
    after bringing up the application and logging in as "root"
    */
    public void generatePrivateKeys() {
        CryptoKey key = CryptoKey.find(GWConfig.getPublicKeyId(), CryptoKey.PURPOSE_SIGNING);
        if (key.getRawRecord().isNewRecord()) {
            KeyPair pair = Crypt.getInstance().generateKeyPair(Request.SIGNATURE_ALGO, Request.SIGNATURE_ALGO_KEY_LENGTH);
            key.setAlgorithm(Request.SIGNATURE_ALGO);
            key.setPrivateKey(Crypt.getInstance().getBase64Encoded(pair.getPrivate()));
            key.setPublicKey(Crypt.getInstance().getBase64Encoded(pair.getPublic()));
            key.save();
        }

        CryptoKey encryptionKey = CryptoKey.find(GWConfig.getPublicKeyId(), CryptoKey.PURPOSE_ENCRYPTION);
        if (encryptionKey.getRawRecord().isNewRecord()) {
            KeyPair pair = Crypt.getInstance().generateKeyPair(Request.ENCRYPTION_ALGO, Request.ENCRYPTION_ALGO_KEY_LENGTH);
            encryptionKey.setAlgorithm(Request.ENCRYPTION_ALGO);
            encryptionKey.setPrivateKey(Crypt.getInstance().getBase64Encoded(pair.getPrivate()));
            encryptionKey.setPublicKey(Crypt.getInstance().getBase64Encoded(pair.getPublic()));
            encryptionKey.save();
        }
    }

    /*
        Call this from the controller manually to subscribe on the network.

     */
    public static void registerBecknKeys() {

        Subscriber subscriber = new Subscriber();
        subscriber.setSubscriberId(GWConfig.getSubscriberId());
        subscriber.setNonce(Base64.getEncoder().encodeToString(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)));
        subscriber.setUniqueKeyId(GWConfig.getPublicKeyId());
        subscriber.setCountry("IND");
        CryptoKey signingKey = CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_SIGNING);

        subscriber.setSigningPublicKey(Request.getRawSigningKey(CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_SIGNING).getPublicKey()));
        subscriber.setEncrPublicKey(Request.getRawEncryptionKey(CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPublicKey()));
        subscriber.setValidFrom(signingKey.getUpdatedAt());
        subscriber.setSubscriberUrl(Config.instance().getServerBaseUrl()+"/bg");
        subscriber.setValidTo(new Date(signingKey.getUpdatedAt().getTime() + (long) (10L * 365.25D * 24L * 60L * 60L * 1000L)));
        subscriber.setType(Subscriber.SUBSCRIBER_TYPE_BG);
        Request request = new Request(subscriber.getInner());

        TaskManager.instance().executeAsync((Task) () -> {
            List<Subscriber> subscriberList = BecknPublicKeyFinder.lookup(subscriber);
            List<String> apis = new ArrayList<>();
            if (subscriberList.isEmpty()) {
                apis.add("register");
            }
            apis.add("subscribe");


            for (String api : apis) {
                Call<JSONObject> call = new Call<JSONObject>().url(GWConfig.getRegistryUrl() + "/" + api).method(HttpMethod.POST).input(subscriber.getInner()).inputFormat(InputFormat.JSON).
                        header("Content-Type", MimeType.APPLICATION_JSON.toString()).
                        header("Accept", MimeType.APPLICATION_JSON.toString());
                if (api.equals("subscribe")) {
                    call.header("Authorization", request.generateAuthorizationHeader(GWConfig.getSubscriberId(), GWConfig.getPublicKeyId()));
                }
                JSONObject response = call.getResponseAsJson();
            }
        }, false);

    }
}

