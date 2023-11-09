package io.github.gaming32.classorganizer;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClassOrganizer {
    public static ClassOrganizeMap organize(Path root) throws IOException {
        final ClassOrganizeMap result = new ClassOrganizeMap(createInitial(root));
        final Map<String, ClassReader> readers = openClasses(root, result.classSet());
        final var accessMap = createAccessMap(readers);

        for (final var entry : readers.entrySet()) {
            final String className = entry.getKey();
            final Integer thisPackage = result.getPackage(className);

            class Checkers {
                // No checkers bot for you, sorry
                void checkSignature(String signature, boolean isSimpleType) {
                    if (signature == null) return;
                    final SignatureVisitor visitor = new SignatureVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitClassType(String name) {
                            checkClass(name);
                        }
                    };
                    if (isSimpleType) {
                        new SignatureReader(signature).acceptType(visitor);
                    } else {
                        new SignatureReader(signature).accept(visitor);
                    }
                }

                void checkType(Type type) {
                    if (type.getSort() == Type.METHOD) {
                        for (final Type arg : type.getArgumentTypes()) {
                            checkType(arg);
                        }
                        checkType(type.getReturnType());
                        return;
                    }
                    if (type.getSort() == Type.ARRAY) {
                        checkType(type.getElementType());
                        return;
                    }
                    if (type.getSort() != Type.OBJECT) return;
                    checkClass(type.getInternalName());
                }

                AnnotationVisitor checkAnnotation(String descriptor) {
                    checkType(Type.getType(descriptor));
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            checkObject(value);
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                            checkType(Type.getType(descriptor));
                            return this;
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            return this;
                        }

                        @Override
                        public void visitEnum(String name, String descriptor, String value) {
                            final Type type = Type.getType(descriptor);
                            checkMember(new MemberReference(type.getInternalName(), value, type));
                        }
                    };
                }

                void checkObject(Object value) {
                    if (value instanceof Handle handle) {
                        checkMember(new MemberReference(handle.getOwner(), handle.getName(), Type.getType(handle.getDesc())));
                    } else if (value instanceof ConstantDynamic condy) {
                        checkType(Type.getType(condy.getDescriptor()));
                        checkObject(condy.getBootstrapMethod());
                        for (int i = 0; i < condy.getBootstrapMethodArgumentCount(); i++) {
                            checkObject(condy.getBootstrapMethodArgument(i));
                        }
                    } else if (value instanceof Type type) {
                        checkType(type);
                    }
                }

                void checkMember(MemberReference member) {
                    if (checkClass(member.owner())) return;
                    final int access = accessMap.get(member.owner()).get(member);
                    if (isPackagePrivate(access)) {
                        result.mergePackages(thisPackage, result.getPackage(member.owner()));
                    } else if (Modifier.isProtected(access)) {
                        String checkClazz = entry.getValue().getSuperName();
                        while (checkClazz != null) {
                            if (checkClazz.equals(member.owner())) return;
                            final ClassReader reader = readers.get(checkClazz);
                            if (reader == null) return;
                            checkClazz = reader.getSuperName();
                        }
                        // The member is protected and not inherited. Merge.
                        result.mergePackages(thisPackage, result.getPackage(member.owner()));
                    }
                }

                /**
                 * @return {@code true} if already merged
                 */
                boolean checkClass(String otherClass) {
                    if (otherClass.equals(className)) {
                        return true;
                    }
                    final Integer otherPackage = result.getPackage(otherClass);
                    if (otherPackage == null || thisPackage.equals(otherPackage)) {
                        return true;
                    }
                    final int otherAccess = readers.get(otherClass).getAccess();
                    if (!isPackagePrivate(otherAccess)) {
                        return false;
                    }
                    result.mergePackages(thisPackage, otherPackage);
                    return false;
                }
            }
            final Checkers ch = new Checkers();

            entry.getValue().accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    ch.checkSignature(signature, false);
                    if (superName != null) {
                        ch.checkClass(superName);
                    }
                    if (interfaces != null) {
                        for (final String intf : interfaces) {
                            ch.checkClass(intf);
                        }
                    }
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return ch.checkAnnotation(descriptor);
                }

                @Override
                public void visitInnerClass(String name, String outerName, String innerName, int access) {
                    ch.checkClass(name);
                    if (outerName != null) {
                        ch.checkClass(outerName);
                    }
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                    return ch.checkAnnotation(descriptor);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    ch.checkType(Type.getType(descriptor));
                    ch.checkSignature(signature, true);
                    if (value != null) {
                        ch.checkObject(value);
                    }
                    return new FieldVisitor(api) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    ch.checkType(Type.getMethodType(descriptor));
                    ch.checkSignature(signature, false);
                    if (exceptions != null) {
                        for (final String exc : exceptions) {
                            ch.checkClass(exc);
                        }
                    }
                    return new MethodVisitor(api) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            ch.checkMember(new MemberReference(owner, name, Type.getType(descriptor)));
                        }

                        @Override
                        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                            ch.checkType(Type.getType(descriptor));
                            ch.checkSignature(signature, true);
                        }

                        @Override
                        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                            ch.checkType(Type.getMethodType(descriptor));
                            ch.checkObject(bootstrapMethodHandle);
                            for (final Object arg : bootstrapMethodArguments) {
                                ch.checkObject(arg);
                            }
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            ch.checkObject(value);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            ch.checkMember(new MemberReference(owner, name, Type.getMethodType(descriptor)));
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                            ch.checkType(Type.getType(descriptor));
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            if (type != null) {
                                ch.checkClass(type);
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            ch.checkClass(type);
                        }
                    };
                }

                @Override
                public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                    ch.checkType(Type.getType(descriptor));
                    ch.checkSignature(signature, false);
                    return new RecordComponentVisitor(api) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }

                        @Override
                        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                            return ch.checkAnnotation(descriptor);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return result.compacted();
    }

    private static Map<String, Integer> createInitial(Path root) throws IOException {
        final int[] counter = new int[1];
        try (Stream<Path> stream = Files.find(root, Integer.MAX_VALUE, (p, a) -> a.isRegularFile() && p.toString().endsWith(".class"))) {
            return stream.collect(Collectors.toMap(
                path -> {
                    final String result = root.relativize(path)
                        .toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                    return result.substring(0, result.length() - 6);
                },
                p -> counter[0]++
            ));
        }
    }

    private static Map<String, ClassReader> openClasses(Path root, Collection<String> classes) throws IOException {
        try {
            return classes.parallelStream().collect(Collectors.toMap(
                Function.identity(),
                className -> {
                    final Path path = root.resolve(
                        className.replace("/", root.getFileSystem().getSeparator()).concat(".class")
                    );
                    try (InputStream is = Files.newInputStream(path)) {
                        return new ClassReader(is);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            ));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Map<String, Map<MemberReference, Integer>> createAccessMap(Map<String, ClassReader> classes) {
        return classes.entrySet().parallelStream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> {
                final Map<MemberReference, Integer> result = new HashMap<>();
                entry.getValue().accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        result.put(new MemberReference(entry.getKey(), name, Type.getType(descriptor)), access);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        result.put(new MemberReference(entry.getKey(), name, Type.getMethodType(descriptor)), access);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                return result;
            }
        ));
    }

    private static boolean isPackagePrivate(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0;
    }
}
