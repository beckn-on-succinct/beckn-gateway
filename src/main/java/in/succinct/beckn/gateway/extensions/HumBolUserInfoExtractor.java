package in.succinct.beckn.gateway.extensions;

import com.venky.extension.Registry;
import com.venky.swf.controller.OidController.OIDProvider;
import com.venky.swf.extensions.SocialLoginInfoExtractor;
import com.venky.swf.integration.JSON;
import com.venky.swf.routing.Config;
import org.apache.oltu.oauth2.client.response.OAuthResourceResponse;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.InputStreamReader;
import java.util.Stack;

public class HumBolUserInfoExtractor extends SocialLoginInfoExtractor {
    static {
        Registry.instance().registerExtension(SocialLoginInfoExtractor.class.getName(),new HumBolUserInfoExtractor());
    }

    @Override
    public JSONObject extractUserInfo(OIDProvider provider, OAuthResourceResponse resourceResponse) {
        JSONObject userInfo = (JSONObject) JSONValue.parse(new InputStreamReader(resourceResponse.getBodyAsInputStream()));
        //FormatHelper.instance(userInfo).change_key_case(KeyCase.CAMEL,KeyCase.SNAKE);
        if (userInfo.containsKey("user")){
            userInfo = (JSONObject) userInfo.remove("user");
        }
        cleanUpId(userInfo);
        Config.instance().getLogger(getClass().getName()).info("Found user :\n" +  userInfo);
        return userInfo;
    }
    private void cleanUpId(JSONObject userInfo){
        Stack<JSONObject> s = new Stack<>();
        s.push(userInfo);
        while (!s.isEmpty()){
            JSON e = new JSON(s.pop());
            e.removeAttribute("id");
            for (String name : e.getElementAttributeNames()){
                s.push(e.getElementAttribute(name));
            }
            for (String name : e.getArrayElementNames()){
                for (JSONObject o : e.getArrayElements(name)){
                    s.push(o);
                }
            }
        }

    }
}
