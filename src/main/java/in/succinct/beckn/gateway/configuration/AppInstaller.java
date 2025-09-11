package in.succinct.beckn.gateway.configuration;

import com.venky.core.io.StringReader;
import com.venky.core.security.Crypt;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.configuration.Installer;
import com.venky.swf.db.Database;
import com.venky.swf.db.annotations.column.DATA_TYPE;
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
import in.succinct.beckn.gateway.db.model.LlmSystemPrompt;
import in.succinct.beckn.gateway.db.model.json.LLMMessage;
import in.succinct.beckn.gateway.extensions.BecknPublicKeyFinder;
import in.succinct.beckn.gateway.util.GWConfig;
import in.succinct.onet.core.adaptor.NetworkAdaptor;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;
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
        loadSysPrompts();
    }
    
    private void loadSysPrompts() {
        String sysPrompt = """
You are a shopping assistant.
Your ONLY job is to return valid JSON objects according to the rules below.
Never write plain text explanations. Never include extra commentary.

Rules:

If the user wants to search for a product or category:
{
    "descriptor": {
        "long_desc": "..."
    }
}

If the user wants to search for products from a seller:
{
    "provider": {
        "descriptor": {
            "long_desc": "..."
        }
    }
}

If the user wants to search for a product AND specifies a seller:
{
    "descriptor": {
        "long_desc": "..."
    },
    "provider": {
        "descriptor": {
            "long_desc": "..."
        }
    }
}

If the user wants to search near their location with in a specified distance (with or without a product/seller), include the location attribute:
{
    "location": {
        "circle": {
            "radius": {
                "value": "...",
                "unit": "km|mile"
            }
        }
    }
}

If no distance is specified by the user, use:
{
    "location": {
        "circle": {
            "radius": {
                "value": "10",
                "unit": "km"
            }
        }
    }
}

Always merge applicable attributes into a single JSON response.

The response MUST always be valid JSON. No comments, no text outside the JSON.
""";
        
        LlmSystemPrompt prompt = Database.getTable(LlmSystemPrompt.class).newRecord();
        prompt.setContent(new StringReader(sysPrompt));
        prompt.setLLmEngine(Config.instance().getProperty("in.succinct.llm.engine","llama"));
        prompt.setAssistant("search");
        prompt = Database.getTable(LlmSystemPrompt.class).getRefreshed(prompt);
        if (prompt.getRawRecord().isNewRecord()) {
            prompt.save();
        }
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
        TaskManager.instance().executeAsync((Task) () -> {
            NetworkAdaptorFactory.getInstance().getAdaptor().subscribe(GWConfig.getSubscriber());
            NetworkAdaptorFactory.getInstance().getAdaptor().subscribe(GWConfig.getSubscriber(Subscriber.SUBSCRIBER_TYPE_BAP));
        }, false);

    }
}

