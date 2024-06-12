package in.succinct.beckn.gateway.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;

import java.util.StringTokenizer;

public class JsonAttributeSetter {
    String key;
    JsonAttributeSetter prev;
    JsonAttributeSetter next;
    JSONAware jsonAware = null;

    public JsonAttributeSetter(String key) {
        this(null, new StringTokenizer(key, ".[]"));
    }

    public JsonAttributeSetter(JsonAttributeSetter prev, StringTokenizer keyTokenizer) {
        this.key = keyTokenizer.nextToken();
        this.prev = prev;

        if (keyTokenizer.hasMoreTokens()) {
            this.next = new JsonAttributeSetter(this, keyTokenizer);
        }
    }

    public boolean isKeyInteger() {
        return key.matches("^[0-9]+$");
    }

    public boolean isLeaf() {
        return next == null;
    }

    public boolean isFirst() {
        return prev == null;
    }

    @SuppressWarnings("unchecked")
    public void setNextJsonAware(JSONAware nextJsonAware){
        if (jsonAware instanceof JSONObject){
            ((JSONObject)jsonAware).put(key,nextJsonAware);
        }else if (jsonAware instanceof JSONArray){
            ((JSONArray)jsonAware).set(Integer.parseInt(key),nextJsonAware);
        }
    }
    public JSONAware getNextJsonAware(){
        JSONAware nextJsonAware = null;
        if (jsonAware instanceof  JSONObject){
            nextJsonAware = (JSONAware) ((JSONObject)jsonAware).get(key);
        }else if (jsonAware instanceof JSONArray){
            nextJsonAware = (JSONAware) ((JSONArray)jsonAware).get(Integer.parseInt(key));
        }
        if (nextJsonAware == null){
            if (next.isKeyInteger()){
                nextJsonAware = new JSONArray();
            }else {
                nextJsonAware = new JSONObject();
            }
            setNextJsonAware(nextJsonAware);
        }

        return nextJsonAware;
    }

    private void initNext() {
        next.setJsonAware(getNextJsonAware());
    }

    @SuppressWarnings("unchecked")
    public void set(Cell value) {
        Object o = getValue(value);
        if (o == null){
            return;
        }
        if ( jsonAware == null){
            if (isKeyInteger()) {
                this.jsonAware = new JSONArray();
            } else {
                this.jsonAware = new JSONObject();
            }
        }

        if (next == null) {
            if (isKeyInteger()) {
                ensureCapacity((JSONArray) jsonAware);
                ((JSONArray) jsonAware).set(Integer.parseInt(key), o);
            } else {
                ((JSONObject) jsonAware).put(key, o);
            }
        } else {
            if (isKeyInteger()){
                ensureCapacity((JSONArray) jsonAware);
            }
            initNext();
            next.set(value);
        }
    }

    public void setJsonAware(JSONAware jsonAware) {
        this.jsonAware = jsonAware;
    }

    public JSONAware getJsonAware() {
        return jsonAware;
    }

    private void ensureCapacity(JSONArray o) {
        int minSize = Integer.parseInt(key) + 1;
        while (o.size() < minSize) {
            if (next == null){
                o.add(null);
            }else {
                if (next.isKeyInteger()) {
                    o.add(new JSONArray());
                } else {
                    o.add(new JSONObject());
                }
            }
        }
    }

    public Object getValue(Cell value) {
        if (value == null){
            return null;
        }
        switch (value.getCellType()) {
            case BLANK:
                return null;
            case BOOLEAN:
                return value.getBooleanCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(value)) {
                    return value.getDateCellValue();
                } else {
                    return value.getNumericCellValue();
                }
            default:
                return value.getStringCellValue();
        }
    }

}