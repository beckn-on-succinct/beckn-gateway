package in.succinct.beckn.gateway.configuration;

import com.venky.core.security.Crypt;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.configuration.Installer;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.plugins.collab.db.model.CryptoKey;
import com.venky.swf.sql.Select;
import in.succinct.beckn.BecknObject;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.util.GWConfig;
import org.json.simple.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppInstaller implements Installer {

    public void install() {
        generatePrivateKeys();
        registerBecknKeys();
    }

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

    private void registerBecknKeys() {

        Subscriber subscriber = new Subscriber();
        subscriber.setSubscriberId(GWConfig.getSubscriberId());
        subscriber.setNonce(Base64.getEncoder().encodeToString(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)));
        subscriber.setUniqueKeyId(GWConfig.getPublicKeyId());
        subscriber.setCountry("IND");
        CryptoKey signingKey = CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_SIGNING);

        subscriber.setSigningPublicKey(Request.getRawSigningKey(CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_SIGNING).getPublicKey()));
        subscriber.setEncrPublicKey(Request.getRawEncryptionKey(CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPublicKey()));
        subscriber.setValidFrom(signingKey.getUpdatedAt());
        subscriber.setValidTo(new Date(signingKey.getUpdatedAt().getTime() + (long) (10L * 365.25D * 24L * 60L * 60L * 1000L)));
        Request request = new Request(subscriber.getInner());

        TaskManager.instance().executeAsync((Task) () -> {

            JSONObject response = new Call<JSONObject>().url(GWConfig.getRegistryUrl() + "/subscribe").method(HttpMethod.POST).input(subscriber.getInner()).inputFormat(InputFormat.JSON).
                    header("Content-Type", MimeType.APPLICATION_JSON.toString()).
                    header("Accept", MimeType.APPLICATION_JSON.toString()).
                    header("Authorization", request.generateAuthorizationHeader(GWConfig.getSubscriberId(), GWConfig.getPublicKeyId())).
                    getResponseAsJson();
        }, false);

    }
}

