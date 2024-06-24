package in.succinct.beckn.gateway.controller.proxies;

import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.views.View;

@SuppressWarnings("unused")
public interface BppController {
     View act();

     @RequireLogin(false)
     default View search(){ return act() ; }
     @RequireLogin(false)
     default View select(){ return act() ; }
     @RequireLogin(false)
     default View init(){ return act() ; }
     @RequireLogin(false)
     default View confirm(){ return act() ; }
     @RequireLogin(false)
     default View track(){ return act() ; }
     @RequireLogin(false)
     default View update(){ return act() ; }
     @RequireLogin(false)
     default View status(){ return act() ; }
     @RequireLogin(false)
     default View cancel(){ return act() ; }
     @RequireLogin(false)
     default View rating(){ return act() ; }
     @RequireLogin(false)
     default View support(){ return act() ; }
     @RequireLogin(false)
     default View get_cancellation_reasons(){ return act() ; }
     @RequireLogin(false)
     default View get_return_reasons(){ return act() ; }
     @RequireLogin(false)
     default View get_rating_categories(){ return act() ; }
     @RequireLogin(false)
     default View get_feedback_categories(){ return act() ; }
     @RequireLogin(false)
     default View issue() { return act(); }
     @RequireLogin(false)
     default View issue_status() { return act(); }
     @RequireLogin(false)
     default View receiver_recon() { return act(); }


}
