package net.impulsem.transport.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;


public class TlProtoSchemaTest {

    @Test
    public void loadsAndIsSingleton() {
        TlProtoSchema schema = TlProtoSchema.load();
        assertNotNull(schema);
        assertSame(schema, TlProtoSchema.load());
    }

    @Test
    public void constructorLookup() {
        TlProtoSchema schema = TlProtoSchema.load();
        ConstructorSpec message = schema.constructor(1979759059);
        assertNotNull(message);
        assertEquals("MessageValue", message.proto);
        assertEquals("message", message.predicate);
        assertNull(schema.constructor(12345));
    }

    @Test
    public void methodLookup() {
        MethodSpec method = TlProtoSchema.load().method(227648840);
        assertNotNull(method);
        assertEquals("users.getUsers", method.method);
        assertEquals("/v1.users.Users/GetUsers", method.path);
        assertNotNull(method.result);
    }

    @Test
    public void armResolution() {
        TlProtoSchema schema = TlProtoSchema.load();
        TypeSpec type = schema.type("Message");
        assertNotNull(type);
        assertEquals(1979759059, schema.constructorByArm(type, 2).id);
        assertNull(schema.constructorByArm(type, 99));
    }

    @Test
    public void paramModel() {
        ConstructorSpec message = TlProtoSchema.load().constructor(1979759059);
        ParamSpec flags = message.params.get(0);
        assertEquals(ParamSpec.Kind.FLAGS, flags.kind);
        ParamSpec out = message.params.get(1);
        assertEquals(ParamSpec.Kind.TRUE, out.kind);
        assertEquals("flags", out.flagRegister);
        assertEquals(1, out.flagBit);
        assertEquals(1, out.field);
        ParamSpec entities = null;
        for (ParamSpec param : message.params) {
            if (param.name.equals("entities")) {
                entities = param;
            }
        }
        assertNotNull(entities);
        assertEquals(ParamSpec.Kind.VECTOR, entities.kind);
        assertEquals(ParamSpec.Kind.OBJECT, entities.elem.kind);
        assertEquals("MessageEntity", entities.elem.typeName);
        assertEquals("", entities.elem.name);
    }
}
