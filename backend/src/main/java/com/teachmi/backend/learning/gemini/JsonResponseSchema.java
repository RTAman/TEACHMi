package com.teachmi.backend.learning.gemini;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Deliberately bounded schema subset. Unsupported constraints fail before a paid call, never silently vanish. */
final class JsonResponseSchema {
    private static final Set<String> KEYS=Set.of("type","properties","required","additionalProperties","items",
            "minItems","maxItems","minLength","maxLength","minimum","maximum","enum","description","title");
    private static final Set<String> TYPES=Set.of("object","array","string","integer","number","boolean","null");
    private JsonResponseSchema() {}

    static void check(JsonNode schema) { check(schema,0); }
    private static void check(JsonNode s,int depth) {
        if (s==null || !s.isObject() || depth>16) throw bad();
        for (var field:s.properties()) if (!KEYS.contains(field.getKey())) throw bad();
        var types=new HashSet<String>();
        var type=s.path("type");
        if (type.isString()) types.add(type.asText());
        else if (type.isArray()) for(var t:type) { if(!t.isString() || !types.add(t.asText())) throw bad(); }
        if (types.isEmpty() || !TYPES.containsAll(types)) throw bad();
        if (s.has("enum") && (!s.path("enum").isArray() || s.path("enum").isEmpty())) throw bad();
        for(var label:List.of("description","title")) if(s.has(label) && !s.path(label).isString()) throw bad();
        for(var bound:List.of("minItems","maxItems","minLength","maxLength")) {
            if(s.has(bound) && (!s.path(bound).isIntegralNumber() || s.path(bound).decimalValue().signum()<0
                    || s.path(bound).decimalValue().compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE))>0)) throw bad();
        }
        for(var bound:List.of("minimum","maximum")) if(s.has(bound) && !s.path(bound).isNumber()) throw bad();
        for(var pair:List.of(List.of("minItems","maxItems"),List.of("minLength","maxLength"),List.of("minimum","maximum"))) {
            if(s.has(pair.get(0)) && s.has(pair.get(1))
                    && s.path(pair.get(0)).decimalValue().compareTo(s.path(pair.get(1)).decimalValue())>0) throw bad();
        }
        if(types.contains("object")) {
            if (!s.path("properties").isObject() || !s.path("required").isArray()
                    || !s.path("additionalProperties").isBoolean() || s.path("additionalProperties").asBoolean()) throw bad();
            var names=new HashSet<String>();
            for(var required:s.path("required")) {
                if(!required.isString() || !s.path("properties").has(required.asText()) || !names.add(required.asText())) throw bad();
            }
            for(var p:s.path("properties").properties()) check(p.getValue(),depth+1);
        }
        if(types.contains("array")) check(s.get("items"),depth+1);
    }
    static void validate(JsonNode value,JsonNode schema) {
        var type=schema.path("type");
        boolean matches=type.isString()?matches(value,type.asText()):false;
        if(type.isArray()) for(var t:type) matches |= matches(value,t.asText());
        if(!matches) throw bad();
        if(schema.has("enum")) {
            boolean found=false;
            for(var allowed:schema.path("enum")) found |= allowed.equals(value)
                    || (allowed.isNumber() && value.isNumber() && allowed.decimalValue().compareTo(value.decimalValue())==0);
            if(!found) throw bad();
        }
        if(value.isObject()) {
            var props=schema.path("properties");
            for(var req:schema.path("required")) if(!value.has(req.asText())) throw bad();
            for(var entry:value.properties()) {
                if(!props.has(entry.getKey())) throw bad();
                validate(entry.getValue(),props.get(entry.getKey()));
            }
        }
        if(value.isArray()) {
            bounds(value.size(),schema,"minItems","maxItems");
            for(var item:value) validate(item,schema.get("items"));
        }
        if(value.isString()) bounds(value.asText().length(),schema,"minLength","maxLength");
        if(value.isNumber()) {
            if(schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0) throw bad();
            if(schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0) throw bad();
        }
    }
    private static boolean matches(JsonNode n,String type) {
        return switch(type) {
            case "object" -> n.isObject(); case "array" -> n.isArray(); case "string" -> n.isString();
            case "integer" -> n.isNumber() && n.decimalValue().stripTrailingZeros().scale()<=0;
            case "number" -> n.isNumber();
            case "boolean" -> n.isBoolean(); case "null" -> n.isNull(); default -> false;
        };
    }
    private static void bounds(int value,JsonNode s,String min,String max) {
        if((s.has(min) && value<s.get(min).asInt()) || (s.has(max) && value>s.get(max).asInt())) throw bad();
    }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("Invalid response contract"); }
}
