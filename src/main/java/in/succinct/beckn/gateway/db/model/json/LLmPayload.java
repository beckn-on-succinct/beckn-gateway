package in.succinct.beckn.gateway.db.model.json;

import in.succinct.beckn.BecknObject;

public class LLmPayload extends BecknObject {
    public LLmPayload(){
        setModel("default");
        setTemperature(0);
        setTopP(0.9);
        setTopK(1);
    }
    
    
    
    public int getTopK(){
        return getInteger("top_k");
    }
    public void setTopK(int top_k){
        set("top_k",top_k);
    }
    
    
    
    public String getModel(){
        return get("model");
    }
    public void setModel(String model){
        set("model",model);
    }
    
    public double getTemperature(){
        return getDouble("temperature");
    }
    public void setTemperature(double temperature){
        set("temperature",Double.valueOf(temperature).floatValue());
    }
    
    public double getTopP(){
        return getDouble("top_p");
    }
    public void setTopP(double top_p){
        set("top_p",Double.valueOf(top_p).floatValue());
    }
    
    
    public LLMMessages getMessages(){
        return get(LLMMessages.class, "messages",true);
    }
    public void setMessages(LLMMessages messages){
        set("messages",messages);
    }
    
    public LLMMessage getResponse(){
        return get(LLMMessage.class, "response");
    }
    public void setLLMMessage(LLMMessage response){
        set("response",response);
    }
    
    
}
