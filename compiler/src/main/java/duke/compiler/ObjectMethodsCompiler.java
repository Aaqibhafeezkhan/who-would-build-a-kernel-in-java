package duke.compiler;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_char;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_float;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;

/**
 * Does ObjectMethods' job at build time. A record's equals, hashCode and toString are each an
 * invokedynamic naming the record's fields; this generates a class with one static method doing
 * what java.lang.runtime.ObjectMethods would, and the call site becomes a plain static call to it.
 */
final class ObjectMethodsCompiler {

    private static final ClassDesc OBJECT_METHODS = ClassDesc.of("java.lang.runtime.ObjectMethods");
    private static final ClassDesc OBJECTS = ClassDesc.of("java.util.Objects");
    private static final ClassDesc STRING_BUILDER = ClassDesc.of("java.lang.StringBuilder");
    private static final String METHOD = "invoke";

    private final ClassPool pool;
    private int helpers;

    ObjectMethodsCompiler(ClassPool pool) {
        this.pool = pool;
    }

    static boolean isObjectMethods(InvokeDynamicInstruction indy) {
        return indy.bootstrapMethod().owner().equals(OBJECT_METHODS);
    }

    /** Generates the class holding {@code indy}'s method and returns that method. */
    ClassPool.ResolvedMethod methodFor(InvokeDynamicInstruction indy, String callerClass) {
        List<ConstantDesc> args = indy.bootstrapArgs();
        ClassDesc record = (ClassDesc) args.get(0);
        String names = (String) args.get(1);
        List<DirectMethodHandleDesc> getters = new ArrayList<>();
        for (ConstantDesc arg : args.subList(2, args.size())) {
            DirectMethodHandleDesc getter = (DirectMethodHandleDesc) arg;
            if (getter.kind() != DirectMethodHandleDesc.Kind.GETTER) {
                throw new CompileException("record component handle of kind " + getter.kind());
            }
            getters.add(getter);
        }
        String name = indy.name().stringValue();
        MethodTypeDesc type = indy.typeSymbol();

        ClassDesc helper = ClassDesc.ofInternalName(callerClass + "$$ObjectMethods$" + helpers++);
        byte[] bytes = ClassFile.of().build(helper, cb -> {
            cb.withFlags(AccessFlag.FINAL, AccessFlag.SYNTHETIC, AccessFlag.SUPER);
            cb.withSuperclass(CD_Object);
            cb.withMethodBody(METHOD, type, ClassFile.ACC_STATIC, code -> {
                switch (name) {
                    case "toString" -> toString(code, simpleName(record), names, getters);
                    case "equals" -> equals(code, record, getters);
                    case "hashCode" -> hashCode(code, getters);
                    default -> throw new CompileException("ObjectMethods." + name + " is not a record method");
                }
            });
        });
        pool.add(ClassFile.of().parse(bytes));
        return pool.resolveMethod(helper.descriptorString().substring(1, helper.descriptorString().length() - 1),
                METHOD, type.descriptorString());
    }

    /** {@code Point[x=1, y=2]}, each component appended the way string concatenation would. */
    private static void toString(CodeBuilder code, String simpleName, String names, List<DirectMethodHandleDesc> getters) {
        String[] componentNames = names.isEmpty() ? new String[0] : names.split(";");
        code.new_(STRING_BUILDER).dup().ldc(simpleName + "[")
                .invokespecial(STRING_BUILDER, "<init>", MethodTypeDesc.of(CD_void, CD_String));
        for (int i = 0; i < getters.size(); i++) {
            DirectMethodHandleDesc getter = getters.get(i);
            append(code.ldc((i == 0 ? "" : ", ") + componentNames[i] + "="), CD_String);
            append(code.aload(0).getfield(getter.owner(), getter.methodName(), getter.invocationType().returnType()),
                    getter.invocationType().returnType());
        }
        append(code.ldc("]"), CD_String);
        code.invokevirtual(STRING_BUILDER, "toString", MethodTypeDesc.of(CD_String)).areturn();
    }

    private static void append(CodeBuilder code, ClassDesc type) {
        ClassDesc parameter = switch (type.descriptorString()) {
            case "B", "S", "I" -> CD_int;
            case "C" -> CD_char;
            case "J" -> CD_long;
            case "F" -> CD_float;
            case "D" -> CD_double;
            case "Z" -> CD_boolean;
            case "Ljava/lang/String;" -> CD_String;
            default -> CD_Object;
        };
        code.invokevirtual(STRING_BUILDER, "append", MethodTypeDesc.of(STRING_BUILDER, parameter));
    }

    /**
     * Same object, then an instance check, then each component. ObjectMethods nests its component
     * tests so the last one runs first; the order shows when a component's equals has side effects.
     */
    private static void equals(CodeBuilder code, ClassDesc record, List<DirectMethodHandleDesc> getters) {
        Label notSame = code.newLabel();
        Label isInstance = code.newLabel();
        Label unequal = code.newLabel();
        code.aload(0).aload(1).if_acmpne(notSame).iconst_1().ireturn();
        code.labelBinding(notSame);
        code.aload(1).instanceOf(record).ifne(isInstance).iconst_0().ireturn();
        code.labelBinding(isInstance);
        code.aload(1).checkcast(record).astore(2);
        for (DirectMethodHandleDesc getter : getters.reversed()) {
            ClassDesc type = getter.invocationType().returnType();
            code.aload(0).getfield(getter.owner(), getter.methodName(), type);
            code.aload(2).getfield(getter.owner(), getter.methodName(), type);
            switch (type.descriptorString()) {
                case "B", "S", "C", "I", "Z" -> code.if_icmpne(unequal);
                case "J" -> code.lcmp().ifne(unequal);
                case "F" -> code.invokestatic(ClassDesc.of("java.lang.Float"), "compare",
                        MethodTypeDesc.of(CD_int, CD_float, CD_float)).ifne(unequal);
                case "D" -> code.invokestatic(ClassDesc.of("java.lang.Double"), "compare",
                        MethodTypeDesc.of(CD_int, CD_double, CD_double)).ifne(unequal);
                default -> code.invokestatic(OBJECTS, "equals", MethodTypeDesc.of(CD_boolean, CD_Object, CD_Object))
                        .ifeq(unequal);
            }
        }
        code.iconst_1().ireturn();
        code.labelBinding(unequal);
        code.iconst_0().ireturn();
    }

    /** {@code 31 * h + hash(component)} from 0, with each primitive hashed by its wrapper's hashCode. */
    private static void hashCode(CodeBuilder code, List<DirectMethodHandleDesc> getters) {
        code.iconst_0();
        for (DirectMethodHandleDesc getter : getters) {
            ClassDesc type = getter.invocationType().returnType();
            code.bipush(31).imul();
            code.aload(0).getfield(getter.owner(), getter.methodName(), type);
            switch (type.descriptorString()) {
                case "B", "S", "C", "I" -> {
                }
                case "J" -> hashStatic(code, "java.lang.Long", CD_long);
                case "F" -> hashStatic(code, "java.lang.Float", CD_float);
                case "D" -> hashStatic(code, "java.lang.Double", CD_double);
                case "Z" -> hashStatic(code, "java.lang.Boolean", CD_boolean);
                default -> code.invokestatic(OBJECTS, "hashCode", MethodTypeDesc.of(CD_int, CD_Object));
            }
            code.iadd();
        }
        code.ireturn();
    }

    private static void hashStatic(CodeBuilder code, String wrapper, ClassDesc primitive) {
        code.invokestatic(ClassDesc.of(wrapper), "hashCode", MethodTypeDesc.of(CD_int, primitive));
    }

    /** What Class.getSimpleName would say: the InnerClasses name for nested and local records. */
    private String simpleName(ClassDesc record) {
        String internal = record.descriptorString().substring(1, record.descriptorString().length() - 1);
        ClassModel model = pool.get(internal);
        for (var attribute : model.findAttributes(Attributes.innerClasses())) {
            for (InnerClassInfo info : attribute.classes()) {
                if (info.innerClass().asInternalName().equals(internal) && info.innerName().isPresent()) {
                    return info.innerName().get().stringValue();
                }
            }
        }
        return internal.substring(internal.lastIndexOf('/') + 1);
    }
}
