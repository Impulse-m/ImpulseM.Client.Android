package net.impulsem.transport.schema;

import java.util.ArrayList;
import java.util.List;


public final class ConstructorSpec {

    public static final class Capture {
        public int field;
        public String name;
    }


    public int id;
    public String predicate;
    public String type;
    public String proto;
    public Integer arm;
    public List<ParamSpec> params = new ArrayList<ParamSpec>();
    public List<Capture> capture = new ArrayList<Capture>();
}
