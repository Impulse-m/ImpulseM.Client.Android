package net.impulsem.transport.schema;

import java.util.HashMap;
import java.util.Map;


public final class TypeSpec {

    public String name;
    public String proto;
    public boolean polymorphic;
    public Map<Integer, Integer> arms = new HashMap<Integer, Integer>();
}
