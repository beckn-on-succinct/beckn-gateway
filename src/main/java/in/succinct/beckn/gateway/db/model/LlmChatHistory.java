package in.succinct.beckn.gateway.db.model;

import com.venky.swf.db.annotations.column.COLUMN_SIZE;
import com.venky.swf.db.annotations.column.UNIQUE_KEY;
import com.venky.swf.db.annotations.column.indexing.Index;
import com.venky.swf.db.annotations.column.validations.Enumeration;
import com.venky.swf.db.model.Model;

public interface LlmChatHistory extends Model {
    @Index
    String getTransactionId();
    void setTransactionId(String transactionId);
    
    
    @Enumeration(enumClass = "in.succinct.beckn.gateway.db.model.json.LLMMessage$Role")
    String getRole();
    void setRole(String role);
    
    @COLUMN_SIZE(1024)
    String getContent();
    void setContent(String text);
    
}
