package in.succinct.beckn.gateway.controller.proxies;

import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.views.View;

@SuppressWarnings("unused")
public interface BapController {
    View on_act();

    @RequireLogin(value = false)
    default View on_search() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_select() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_init() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_confirm() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_track() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_update() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_status() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_cancel() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_rating() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_support() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View cancellation_reasons() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View return_reasons() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View rating_categories() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View feedback_categories() {
        return on_act();
    }

    @RequireLogin(value = false)
    default View on_issue() { return on_act(); }

    @RequireLogin(value = false)
    default View on_issue_status() { return on_act(); }

    @RequireLogin(value = false)
    default View on_receiver_recon() { return on_act(); }

}
