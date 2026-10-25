package io.metaloom.graph.apilock;

import java.lang.annotation.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.ConstantDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Renders the public Java API of a module as text: one line per element, deterministic, sorted.
 *
 * <p>
 * Every line is {@code <owner> <category> <text>}, where the owner is the binary name of the type the element belongs
 * to. The categories, in the order they sort within a type, are {@code type} (kind, modifiers, type parameters,
 * superclass, interfaces, permitted subclasses), {@code annotation} ({@code @FunctionalInterface}), {@code deprecated}
 * (one line per deprecated element, so deprecating reads as an addition rather than a change), {@code components}
 * (a record's header), {@code constant} (an enum constant and its ordinal), {@code field} (with its value when it is a
 * compile-time constant, because callers inline those), {@code constructor} and {@code method} (full generic signature,
 * {@code default}/{@code static}/{@code abstract}, throws clause).
 * </p>
 *
 * <p>
 * Included: public types, and public or protected nested types whose enclosing types are included; their public and
 * protected members; public and protected members a public class inherits from a superclass that is not itself public,
 * because callers can name those through the public class. Excluded: synthetic and bridge members, anything private or
 * package-private, anonymous and local classes, parameter names (renaming one breaks nobody).
 * </p>
 *
 * <p>
 * Reflection over the compiled classes, loaded without initialising them, so dumping graph-core does not load its native
 * library. Constant values and enum constant order come from the class file through {@code java.lang.classfile} for the
 * same reason: reading them reflectively would run static initialisers.
 * </p>
 */
public final class ApiDump {

	/**
	 * The version of this rendering. Bumped whenever a change to this class changes the text of any line, which makes
	 * every baseline fail with a message saying so rather than with a confusing diff.
	 */
	public static final int FORMAT = 1;

	private static final List<String> CATEGORY_ORDER = List.of("type", "annotation", "deprecated", "components", "constant",
		"field", "constructor", "method");

	private static final int TYPE_MODIFIERS = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.ABSTRACT | Modifier.STATIC
		| Modifier.FINAL;
	private static final int METHOD_MODIFIERS = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.ABSTRACT | Modifier.STATIC
		| Modifier.FINAL;
	private static final int FIELD_MODIFIERS = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL;
	private static final int CONSTRUCTOR_MODIFIERS = Modifier.PUBLIC | Modifier.PROTECTED;

	private ApiDump() {
	}

	/**
	 * The dump of a scope: its resolved {@code scope} lines first, in root order, then every element line sorted.
	 *
	 * @param scope
	 *            what to dump
	 * @param loader
	 *            the loader the module's classes and their dependencies are visible to
	 * @return the lines, without line terminators
	 */
	public static List<String> render(ApiScope scope, ClassLoader loader) {
		Class<?> anchor = load(scope.anchor(), loader);
		try (ClassSource source = ClassSource.of(anchor)) {
			List<String> scopeLines = new ArrayList<>();
			Set<String> typeNames = new TreeSet<>();
			for (ApiScope.Root root : scope.roots()) {
				switch (root.kind()) {
					case MODULE_EXPORTS -> {
						ModuleDescriptor descriptor = ModuleDescriptor.read(ByteBuffer.wrap(source.moduleInfo()
							.orElseThrow(() -> new IllegalStateException("The scope reads the module's exports, but there is no"
								+ " module-info.class in " + source.location()))));
						List<String> exported = descriptor.exports().stream()
							.filter(export -> !export.isQualified())
							.map(ModuleDescriptor.Exports::source)
							.sorted()
							.toList();
						if (exported.isEmpty()) {
							throw new IllegalStateException("Module " + descriptor.name() + " exports no package unconditionally");
						}
						for (String pkg : exported) {
							scopeLines.add("scope module " + descriptor.name() + " exports " + pkg);
							typeNames.addAll(nonEmpty(source.classesIn(pkg), "exported package " + pkg, source));
						}
					}
					case PACKAGE -> {
						scopeLines.add("scope package " + root.name());
						typeNames.addAll(nonEmpty(source.classesIn(root.name()), "package " + root.name(), source));
					}
					case TYPE -> {
						scopeLines.add("scope type " + root.name());
						int dot = root.name().lastIndexOf('.');
						String pkg = dot < 0 ? "" : root.name().substring(0, dot);
						List<String> matching = source.classesIn(pkg).stream()
							.filter(name -> name.equals(root.name()) || name.startsWith(root.name() + "$"))
							.toList();
						if (!matching.contains(root.name())) {
							throw new IllegalStateException("Scope type " + root.name() + " is not in " + source.location());
						}
						typeNames.addAll(matching);
					}
				}
			}

			List<Line> lines = new ArrayList<>();
			for (String name : typeNames) {
				Class<?> type = load(name, loader);
				if (!exposed(type)) {
					continue;
				}
				try {
					renderType(type, source, lines);
				} catch (RuntimeException | LinkageError e) {
					throw new IllegalStateException("Cannot render the API of " + name + ": " + e, e);
				}
			}
			if (lines.isEmpty()) {
				throw new IllegalStateException("The scope resolved to no public type in " + source.location());
			}
			lines.sort(Comparator.comparing(Line::owner)
				.thenComparingInt(line -> CATEGORY_ORDER.indexOf(line.category()))
				.thenComparing(Line::text));

			List<String> out = new ArrayList<>(scopeLines);
			lines.stream().map(Line::toString).distinct().forEach(out::add);
			return out;
		}
	}

	/**
	 * The identity a line keeps across a change, used to pair a removed line with an added one as "changed": the owner,
	 * the category and the member's name. All overloads of a method share one key, which is what makes a changed return
	 * type, or an overload replaced by another, read as one change rather than as an unrelated removal and addition.
	 */
	public static String groupKey(String line) {
		if (line.startsWith("scope ")) {
			return line;
		}
		String[] parts = line.split(" ", 3);
		if (parts.length < 3) {
			return line;
		}
		String owner = parts[0];
		String category = parts[1];
		String text = parts[2];
		String name = switch (category) {
			case "method", "constructor" -> {
				int paren = text.indexOf('(');
				String head = paren < 0 ? text : text.substring(0, paren);
				yield head.substring(head.lastIndexOf(' ') + 1);
			}
			case "field" -> {
				int eq = text.indexOf(" = ");
				String head = eq < 0 ? text : text.substring(0, eq);
				yield head.substring(head.lastIndexOf(' ') + 1);
			}
			case "constant" -> text.split(" ", 2)[0];
			case "deprecated" -> {
				int at = text.indexOf(" @");
				yield at < 0 ? text : text.substring(0, at);
			}
			default -> "";
		};
		return owner + " " + category + " " + name;
	}

	// -----------------------------------------------------------------------------------------------------------------

	private static List<String> nonEmpty(List<String> names, String what, ClassSource source) {
		if (names.isEmpty()) {
			throw new IllegalStateException("The scope names " + what + ", which has no classes in " + source.location());
		}
		return names;
	}

	private static Class<?> load(String name, ClassLoader loader) {
		try {
			return Class.forName(name, false, loader);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Cannot load " + name + " from the classpath", e);
		}
	}

	static boolean exposed(Class<?> type) {
		if (type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass() || type.isHidden()) {
			return false;
		}
		int modifiers = type.getModifiers();
		Class<?> outer = type.getDeclaringClass();
		if (outer == null) {
			return Modifier.isPublic(modifiers);
		}
		return (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) && exposed(outer);
	}

	private static boolean exposedMember(Member member) {
		int modifiers = member.getModifiers();
		return !member.isSynthetic() && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers));
	}

	private static void renderType(Class<?> type, ClassSource source, List<Line> out) {
		String owner = type.getName();
		ClassModel model = parse(type, source);

		out.add(new Line(owner, "type", typeDeclaration(type)));
		if (type.isAnnotationPresent(FunctionalInterface.class)) {
			out.add(new Line(owner, "annotation", "@java.lang.FunctionalInterface"));
		}
		deprecation(type, "type").ifPresent(text -> out.add(new Line(owner, "deprecated", text)));

		if (type.isRecord()) {
			out.add(new Line(owner, "components", Arrays.stream(type.getRecordComponents())
				.map(component -> render(component.getGenericType()) + " " + component.getName())
				.collect(Collectors.joining(", ", "(", ")"))));
		}

		// Enum constants in declaration order, from the class file: getEnumConstants() would initialise the enum.
		int ordinal = 0;
		for (FieldModel field : model.fields()) {
			if (field.flags().has(AccessFlag.ENUM)) {
				out.add(new Line(owner, "constant", field.fieldName().stringValue() + " ordinal=" + ordinal++));
			}
		}

		Set<String> seenFields = new HashSet<>();
		Set<String> seenMethods = new HashSet<>();
		// The type itself, then any non-public superclasses whose public members it exposes.
		for (Class<?> declaring = type; declaring != null; declaring = declaring.getSuperclass()) {
			if (declaring != type && (Modifier.isPublic(declaring.getModifiers()) || declaring.isInterface())) {
				break;
			}
			Map<String, ConstantDesc> constantValues = constantValues(declaring == type ? model : parse(declaring, source));
			for (Field field : declaring.getDeclaredFields()) {
				if (!exposedMember(field) || field.isEnumConstant() || !seenFields.add(field.getName())) {
					continue;
				}
				String text = modifiers(field.getModifiers(), FIELD_MODIFIERS) + render(field.getGenericType()) + " "
					+ field.getName();
				if (constantValues.containsKey(field.getName())) {
					text += " = " + constant(field.getType(), constantValues.get(field.getName()));
				}
				out.add(new Line(owner, "field", text));
				deprecation(field, "field " + field.getName()).ifPresent(d -> out.add(new Line(owner, "deprecated", d)));
			}
			if (declaring == type && !type.isEnum()) {
				for (Constructor<?> constructor : type.getDeclaredConstructors()) {
					if (!exposedMember(constructor)) {
						continue;
					}
					String text = modifiers(constructor.getModifiers(), CONSTRUCTOR_MODIFIERS)
						+ typeParameters(constructor.getTypeParameters()) + type.getSimpleName() + parameters(constructor)
						+ throwsClause(constructor);
					out.add(new Line(owner, "constructor", text));
					deprecation(constructor, "constructor " + type.getSimpleName() + erasedParameters(constructor))
						.ifPresent(d -> out.add(new Line(owner, "deprecated", d)));
				}
			}
			for (Method method : declaring.getDeclaredMethods()) {
				if (!exposedMember(method) || method.isBridge()) {
					continue;
				}
				String key = method.getName() + erasedParameters(method);
				if (!seenMethods.add(key)) {
					continue;
				}
				out.add(new Line(owner, "method", method(method)));
				deprecation(method, "method " + key).ifPresent(d -> out.add(new Line(owner, "deprecated", d)));
			}
		}
	}

	private static ClassModel parse(Class<?> type, ClassSource source) {
		return source.bytes(type.getName()).map(ClassFile.of()::parse)
			.orElseThrow(() -> new IllegalStateException("No class file for " + type.getName() + " in " + source.location()));
	}

	/** Compile-time constant values by field name, from the ConstantValue attributes: callers inline these. */
	private static Map<String, ConstantDesc> constantValues(ClassModel model) {
		Map<String, ConstantDesc> values = new HashMap<>();
		for (FieldModel field : model.fields()) {
			field.findAttribute(Attributes.constantValue())
				.map(ConstantValueAttribute::constant)
				.ifPresent(entry -> values.put(field.fieldName().stringValue(), entry.constantValue()));
		}
		return values;
	}

	private static String typeDeclaration(Class<?> type) {
		int modifiers = type.getModifiers() & TYPE_MODIFIERS;
		String kind;
		if (type.isAnnotation()) {
			kind = "@interface";
		} else if (type.isInterface()) {
			kind = "interface";
		} else if (type.isEnum()) {
			kind = "enum";
		} else if (type.isRecord()) {
			kind = "record";
		} else {
			kind = "class";
		}
		if (type.isInterface()) {
			modifiers &= ~Modifier.ABSTRACT;
		}
		if (type.getDeclaringClass() != null && (type.isInterface() || type.isEnum() || type.isRecord())) {
			modifiers &= ~Modifier.STATIC; // implicitly static
		}
		// An enum is final, or abstract and sealed over the anonymous classes of constants with bodies; a record is
		// always final. Neither is something a caller can act on, and the first changes with an implementation detail.
		boolean enumOrRecord = type.isEnum() || type.isRecord();
		if (enumOrRecord) {
			modifiers &= ~(Modifier.FINAL | Modifier.ABSTRACT);
		}
		StringBuilder text = new StringBuilder(modifiers(modifiers, TYPE_MODIFIERS));
		if (type.isSealed() && !enumOrRecord) {
			text.append("sealed ");
		}
		text.append(kind).append(' ').append(type.getSimpleName()).append(typeParameters(type.getTypeParameters()).stripTrailing());

		Type superclass = type.getGenericSuperclass();
		if (superclass != null && superclass != Object.class && !type.isEnum() && !type.isRecord()) {
			text.append(" extends ").append(render(superclass));
		}
		List<String> interfaces = Arrays.stream(type.getGenericInterfaces())
			.filter(iface -> iface != Annotation.class)
			.map(ApiDump::render)
			.sorted()
			.toList();
		if (!interfaces.isEmpty()) {
			text.append(type.isInterface() ? " extends " : " implements ").append(String.join(", ", interfaces));
		}
		if (type.isSealed() && !enumOrRecord) {
			text.append(" permits ").append(Arrays.stream(type.getPermittedSubclasses())
				.map(Class::getName)
				.sorted()
				.collect(Collectors.joining(", ")));
		}
		return text.toString();
	}

	private static String method(Method method) {
		StringBuilder text = new StringBuilder();
		int modifiers = method.getModifiers() & METHOD_MODIFIERS;
		text.append(modifiers(modifiers & (Modifier.PUBLIC | Modifier.PROTECTED), METHOD_MODIFIERS));
		if (method.isDefault()) {
			text.append("default ");
		}
		text.append(modifiers(modifiers & ~(Modifier.PUBLIC | Modifier.PROTECTED), METHOD_MODIFIERS));
		text.append(typeParameters(method.getTypeParameters()));
		text.append(render(method.getGenericReturnType())).append(' ').append(method.getName());
		text.append(parameters(method)).append(throwsClause(method));
		Object defaultValue = method.getDefaultValue();
		if (defaultValue != null) {
			text.append(" default ").append(annotationValue(defaultValue));
		}
		return text.toString();
	}

	private static String parameters(Executable executable) {
		Type[] types = executable.getGenericParameterTypes();
		List<String> rendered = new ArrayList<>();
		for (int i = 0; i < types.length; i++) {
			String type = render(types[i]);
			if (executable.isVarArgs() && i == types.length - 1 && type.endsWith("[]")) {
				type = type.substring(0, type.length() - 2) + "...";
			}
			rendered.add(type);
		}
		return rendered.stream().collect(Collectors.joining(", ", "(", ")"));
	}

	private static String erasedParameters(Executable executable) {
		return Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(",", "(", ")"));
	}

	private static String throwsClause(Executable executable) {
		List<String> thrown = Arrays.stream(executable.getGenericExceptionTypes()).map(ApiDump::render).sorted().toList();
		return thrown.isEmpty() ? "" : " throws " + String.join(", ", thrown);
	}

	private static String typeParameters(TypeVariable<?>[] variables) {
		if (variables.length == 0) {
			return "";
		}
		return Arrays.stream(variables).map(variable -> {
			List<Type> bounds = Arrays.stream(variable.getBounds()).filter(bound -> bound != Object.class).toList();
			return bounds.isEmpty() ? variable.getName()
				: variable.getName() + " extends " + bounds.stream().map(ApiDump::render).collect(Collectors.joining(" & "));
		}).collect(Collectors.joining(", ", "<", "> "));
	}

	private static String modifiers(int modifiers, int mask) {
		String text = Modifier.toString(modifiers & mask);
		return text.isEmpty() ? "" : text + " ";
	}

	private static Optional<String> deprecation(AnnotatedElement element, String key) {
		Deprecated deprecated = element.getAnnotation(Deprecated.class);
		if (deprecated == null) {
			return Optional.empty();
		}
		return Optional.of(key + " @java.lang.Deprecated(since=" + quote(deprecated.since()) + ", forRemoval="
			+ deprecated.forRemoval() + ")");
	}

	/** A generic type as source would spell it, with binary names ({@code Outer$Inner}) so nothing is ambiguous. */
	static String render(Type type) {
		if (type instanceof Class<?> c) {
			return c.isArray() ? render(c.getComponentType()) + "[]" : c.getName();
		}
		if (type instanceof ParameterizedType p) {
			Class<?> raw = (Class<?>) p.getRawType();
			String head = p.getOwnerType() instanceof ParameterizedType owner ? render(owner) + "$" + raw.getSimpleName()
				: raw.getName();
			return head + Arrays.stream(p.getActualTypeArguments()).map(ApiDump::render).collect(Collectors.joining(", ", "<", ">"));
		}
		if (type instanceof WildcardType w) {
			if (w.getLowerBounds().length > 0) {
				return "? super " + Arrays.stream(w.getLowerBounds()).map(ApiDump::render).collect(Collectors.joining(" & "));
			}
			List<Type> upper = Arrays.stream(w.getUpperBounds()).filter(bound -> bound != Object.class).toList();
			return upper.isEmpty() ? "?" : "? extends " + upper.stream().map(ApiDump::render).collect(Collectors.joining(" & "));
		}
		if (type instanceof TypeVariable<?> v) {
			return v.getName();
		}
		if (type instanceof GenericArrayType g) {
			return render(g.getGenericComponentType()) + "[]";
		}
		throw new IllegalArgumentException("Unknown type " + type);
	}

	private static String constant(Class<?> fieldType, ConstantDesc value) {
		if (fieldType == boolean.class) {
			return ((Integer) value) != 0 ? "true" : "false";
		}
		if (fieldType == char.class) {
			return "'" + escape(String.valueOf((char) (int) (Integer) value)) + "'";
		}
		if (value instanceof String s) {
			return quote(s);
		}
		if (fieldType == long.class) {
			return value + "L";
		}
		if (fieldType == float.class) {
			return value + "f";
		}
		return String.valueOf(value);
	}

	private static String annotationValue(Object value) {
		if (value instanceof String s) {
			return quote(s);
		}
		if (value instanceof Class<?> c) {
			return render(c) + ".class";
		}
		if (value instanceof Enum<?> e) {
			return e.getDeclaringClass().getName() + "." + e.name();
		}
		if (value.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(value);
			List<String> items = new ArrayList<>();
			for (int i = 0; i < length; i++) {
				items.add(annotationValue(java.lang.reflect.Array.get(value, i)));
			}
			return items.stream().collect(Collectors.joining(", ", "{", "}"));
		}
		return String.valueOf(value);
	}

	private static String quote(String s) {
		return "\"" + escape(s) + "\"";
	}

	/** Keeps every line one line: quotes, backslashes and anything outside printable ASCII are escaped. */
	private static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\'' -> out.append("\\'");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20 || c > 0x7e) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

	private record Line(String owner, String category, String text) {

		@Override
		public String toString() {
			return owner + " " + category + " " + text;
		}
	}
}
