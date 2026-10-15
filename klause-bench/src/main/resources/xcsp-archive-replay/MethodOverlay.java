import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MethodOverlay {
    public static void main(String[] args) throws Exception {
        var api = ClassFile.of();
        var original = api.parse(Files.readAllBytes(Path.of(args[0])));
        var replacement = api.parse(Files.readAllBytes(Path.of(args[1])));
        if (!original.thisClass().asInternalName().equals(replacement.thisClass().asInternalName())) {
            throw new IllegalArgumentException("Overlay class names differ");
        }
        var names = new HashSet<>(Arrays.asList(args).subList(3, args.length));
        Set<String> changed = new HashSet<>();
        byte[] bytes = api.transformClass(original, (builder, element) -> {
            if (element instanceof MethodModel method && names.contains(method.methodName().stringValue())) {
                var matches = replacement.methods().stream()
                    .filter(m -> m.methodName().equalsString(method.methodName().stringValue()))
                    .toList();
                if (matches.size() != 1 || !matches.getFirst().methodType().equalsString(method.methodType().stringValue())
                        || !changed.add(method.methodName().stringValue())) {
                    throw new IllegalArgumentException("Overlay method is missing or ambiguous");
                }
                builder.with(matches.getFirst());
            } else {
                builder.with(element);
            }
        });
        if (!changed.equals(names)) throw new IllegalArgumentException("Overlay did not replace every method");
        var errors = api.verify(bytes);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
        Files.write(Path.of(args[2]), bytes);
    }
}
