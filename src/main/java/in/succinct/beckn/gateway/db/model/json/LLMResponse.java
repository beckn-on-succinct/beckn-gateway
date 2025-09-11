package in.succinct.beckn.gateway.db.model.json;

import in.succinct.beckn.BecknObject;
import in.succinct.beckn.BecknObjects;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class LLMResponse extends BecknObject {
    public LLMResponse() {
    }
    
    public LLMResponse(String payload) {
        super(payload);
    }
    
    public LLMResponse(JSONObject object) {
        super(object);
    }
    
    public Choices getChoices(){
        return get(Choices.class, "choices");
    }
    public void setChoices(Choices choices){
        set("choices",choices);
    }
    
    
    
    public static class Choice extends BecknObject {
        public Choice() {
        }
        
        public Choice(String payload) {
            super(payload);
        }
        
        public Choice(JSONObject object) {
            super(object);
        }
        
        public String getFinishReason(){
            return get("finish_reason");
        }
        public void setFinishReason(String finish_reason){
            set("finish_reason",finish_reason);
        }
        public int getIndex(){
            return getInteger("index");
        }
        public void setIndex(int index){
            set("index",index);
        }
        
        public LLMMessage getMessage(){
            return get(LLMMessage.class, "message");
        }
        public void setMessage(LLMMessage llm_message){
            set("message",llm_message);
        }
    }
    public static class Choices extends BecknObjects<Choice> {
        public Choices() {
        }
        
        public Choices(JSONArray value) {
            super(value);
        }
        
        public Choices(String payload) {
            super(payload);
        }
    }
}
