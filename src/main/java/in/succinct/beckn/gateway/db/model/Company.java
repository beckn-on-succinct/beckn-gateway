package in.succinct.beckn.gateway.db.model;

public interface Company extends com.venky.swf.plugins.collab.db.model.participants.admin.Company {
    public String getSubscriberId();
    public void setSubscriberId(String subscriberId);

}
