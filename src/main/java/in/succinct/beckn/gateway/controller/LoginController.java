package in.succinct.beckn.gateway.controller;

import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.path.Path;
import com.venky.swf.routing.Config;
import com.venky.swf.views.View;

public class LoginController extends Controller {
    public LoginController(Path path) {
        super(path);
    }

    @Override
    public View index() {
        if (ObjectUtil.equals("GET",getPath().getRequest().getMethod())) {
            if (Config.instance().getOpenIdProviders().isEmpty()){
                return super.login();
            }else {
                return super.index();
            }
        }else {
            return super.login();
        }
    }
}
