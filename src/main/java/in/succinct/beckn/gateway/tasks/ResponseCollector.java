package in.succinct.beckn.gateway.tasks;

import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.HttpTask;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.DelayedView;
import com.venky.swf.views.View;
import com.venky.swf.views._IView;
import in.succinct.beckn.Request;
import in.succinct.beckn.gateway.controller.NetworkController.Requests;
import in.succinct.beckn.gateway.controller.proxies.ResponseSynchronizer;
import in.succinct.beckn.gateway.controller.proxies.ResponseSynchronizer.Tracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ResponseCollector extends HttpTask {
    Tracker tracker ;
    
    @Override
    public boolean isDatabaseAccessed() {
        return false;
    }
    
    public ResponseCollector(Path path, Tracker tracker){
        super(path);
        this.tracker = tracker;
        tracker.registerListener(this);
    }
    final Requests requests = new Requests();
    
    @Override
    public _IView createView(){
        Request response ;
        while ((response = tracker.nextResponse()) != null) {
            requests.add(response);
        }
        if (tracker.isComplete()) {
            ResponseSynchronizer.getInstance().closeTracker(tracker.getMessageId());
            return new BytesView((Path)getPath(), requests.getInner().toString().getBytes(StandardCharsets.UTF_8), MimeType.APPLICATION_JSON);
        }else {
            tracker.registerListener(this);
            return new DelayedView(getPath());
        }
    }
}
