package io.github.gaming32.classorganizer;

import org.objectweb.asm.Type;

public record MemberReference(String owner, String name, Type descriptor) {
}
