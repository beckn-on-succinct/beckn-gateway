package in.succinct.beckn.gateway.db.model.json;

import in.succinct.beckn.BecknObject;

public class LLMMessage extends BecknObject {
    public Role getRole(){
        return getEnum(Role.class, "role");
    }
    public void setRole(Role role){
        setEnum("role",role);
    }

    public String getContent(){
        return get("content");
    }
    public void setContent(String content){
        set("content",content);
    }
    
    
    public enum Role {
        system,
        assistant,
        user,
    }
    
    
}
