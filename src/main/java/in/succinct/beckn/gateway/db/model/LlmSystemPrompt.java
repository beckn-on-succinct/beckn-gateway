package in.succinct.beckn.gateway.db.model;

import com.venky.swf.db.annotations.column.COLUMN_DEF;
import com.venky.swf.db.annotations.column.UNIQUE_KEY;
import com.venky.swf.db.annotations.column.defaulting.StandardDefault;
import com.venky.swf.db.annotations.column.validations.Enumeration;
import com.venky.swf.db.model.Model;

import java.io.Reader;

public interface LlmSystemPrompt extends Model {
    @Enumeration("llama")
    @UNIQUE_KEY
    String getLLmEngine();
    void setLLmEngine(String lLmEngine);
    
    @Enumeration("search")
    @UNIQUE_KEY
    String getAssistant();
    void setAssistant(String assistant);
    
    @COLUMN_DEF(value = StandardDefault.SOME_VALUE,args = "/v1/chat/completions")
    String getChatUrlPath();
    void setChatUrlPath(String chatUrl);
    
    Reader getContent();
    void setContent(Reader text);
    
}
