/**
 * A native-code backed graph storage engine over memory mapped binary files.
 *
 * <p>
 * The module is named rather than automatic so that the Foreign Function and Memory API can be enabled for this module
 * alone. Native access is a module-level permission: without a name, a consumer has to grant
 * {@code --enable-native-access=ALL-UNNAMED}, which grants it to everything on the classpath at once. With one, the
 * grant is {@code --enable-native-access=io.metaloom.graph.core} and reaches only this code.
 * </p>
 *
 * <p>
 * The internal packages are deliberately not exported. Ids, segments, pages, the log and the codecs are all reachable
 * from the tests, which run on the classpath, but nothing outside should be able to hold a page or a raw id: the
 * addressing invariant is only safe while the engine controls what a caller can name.
 * </p>
 *
 * @see <a href="../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a>
 */
module io.metaloom.graph.core {

	requires org.slf4j;

	exports io.metaloom.graph.core.api;
}
