package in.succinct.beckn.gateway.controller;

import com.venky.swf.controller.Controller;
import com.venky.swf.path.Path;
import com.venky.swf.routing.Config;
import com.venky.swf.views.View;

public class LogoutController extends Controller {
    public LogoutController(Path path) {
        super(path);
    }

    @Override
    public View index() {
        invalidateSession();
        if (Config.instance().getOpenIdProviders().isEmpty()){
            return super.logout();
        }else {
            return super.index();
        }
    }
}
