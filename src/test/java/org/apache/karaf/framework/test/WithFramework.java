/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.framework.test;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.apache.karaf.framework.ContextualFramework;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

@Target(TYPE)
@Retention(RUNTIME)
@ExtendWith(WithFramework.Extension.class)
public @interface WithFramework {
    @Target(FIELD)
    @Retention(RUNTIME)
    @interface Service {
    }

    @Retention(RUNTIME)
    @interface Entry {
        String path();
        String prefix() default "";
    }

    String[] dependencies() default "target/${test}/*.jar";
    Entry[] includeResources() default {};

    class Extension implements BeforeAllCallback, AfterAllCallback, TestInstancePostProcessor, ParameterResolver {

        private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(Extension.class.getName());

        @Override
        public void beforeAll(final ExtensionContext extensionContext) {
            final Thread thread = Thread.currentThread();
            final URL[] urls = createUrls(extensionContext);
            final URLClassLoader loader = new URLClassLoader(urls, thread.getContextClassLoader());
            final ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
            store.put(Context.class, new Context(thread, thread.getContextClassLoader(), loader));
            thread.setContextClassLoader(loader);
            store.put(ContextualFramework.class, new ContextualFramework().start());
        }

        @Override
        public void afterAll(final ExtensionContext extensionContext) {
            final ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
            if (store == null) {
                return;
            }
            ofNullable(store.get(Context.class, Context.class)).ifPresent(Context::close);
            ofNullable(store.get(FileToDelete.class, FileToDelete.class)).ifPresent(f -> {
                if (f.file.exists() && !f.file.delete()) {
                    f.file.deleteOnExit();
                }
            });
            ofNullable(store.get(ContextualFramework.class, ContextualFramework.class)).ifPresent(ContextualFramework::stop);
        }

        private URL[] createUrls(final ExtensionContext context) {
            return context.getElement()
                          .map(e -> e.getAnnotation(WithFramework.class))
                          .map(config -> Stream.concat(Stream.of(config.dependencies())
                                                             .flatMap(it -> Stream.of(
                                                                     variabilize(it, context.getTestClass().map(Class::getName).orElse("default")),
                                                                     variabilize(it, "default")))
                                                             .map(File::new)
                                                             .filter(File::exists)
                                                             .map(f -> {
                                                                 try {
                                                                     return f.toURI().toURL();
                                                                 } catch (final MalformedURLException e) {
                                                                     throw new IllegalArgumentException(e);
                                                                 }
                                                             }), of(config.includeResources())
                                  .filter(it -> it.length > 0)
                                  .map(resources -> {
                                      try {
                                          final File jar = createJar(resources, context.getUniqueId());
                                          context.getStore(NAMESPACE).put(FileToDelete.class, new FileToDelete(jar));
                                          return Stream.of(jar.toURI().toURL());
                                      } catch (final MalformedURLException e) {
                                          throw new IllegalArgumentException(e);
                                      }
                                  })
                                  .orElseGet(Stream::empty))
                                               .toArray(URL[]::new))
                          .orElseGet(() -> new URL[0]);
        }

        private File createJar(final Entry[] resources, final String name) {
            final File out = new File("target/waf/" + name.replace(":", "_").replace("[", "").replace("]", "") + ".jar"); // todo: config
            out.getParentFile().mkdirs();
            try (final JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(out))) {
                final Set<String> createdFolders = new HashSet<>();
                Stream.of(resources).forEach(it -> {
                    try {
                        final File classesRoot = new File("target/test-classes/");
                        final Path classesPath = classesRoot.toPath();
                        final Path root = new File(classesRoot, it.path().replace(".", "/")).toPath();
                        Files.walkFileTree(root,
                                new SimpleFileVisitor<Path>() {
                                    @Override
                                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                                            throws IOException {
                                        String relative = classesPath.relativize(file).toString().substring(it.prefix().length());
                                        if (relative.endsWith("META-INF/MANIFEST.MF")) { // simpler config
                                            relative = "META-INF/MANIFEST.MF";
                                        }
                                        final String[] segments = relative.split("/");
                                        final StringBuilder builder = new StringBuilder(relative.length());
                                        for (int i = 0; i < segments.length - 1; i++) {
                                            builder.append(segments[i]).append('/');
                                            final String folder = builder.toString();
                                            if (createdFolders.add(folder)) {
                                                jarOutputStream.putNextEntry(new JarEntry(folder));
                                                jarOutputStream.closeEntry();
                                            }
                                        }
                                        jarOutputStream.putNextEntry(new JarEntry(relative));
                                        Files.copy(file, jarOutputStream);
                                        jarOutputStream.closeEntry();
                                        return super.visitFile(file, attrs);
                                    }
                                });
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return out;
        }

        private String variabilize(final String name, final String testName) {
            return name.replace("${test}", testName);
        }

        @Override
        public  boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
                throws ParameterResolutionException {
            return supports(parameterContext.getParameter().getType());
        }

        @Override
        public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
                throws ParameterResolutionException {
            return findInjection(extensionContext, parameterContext.getParameter().getType());
        }

        @Override
        public void postProcessTestInstance(final Object testInstance, final ExtensionContext context) {
            Class<?> testClass = context.getRequiredTestClass();
            while (testClass != Object.class) {
                Stream.of(testClass.getDeclaredFields()).filter(c -> c.isAnnotationPresent(Service.class)).forEach(
                        f -> {
                            if (!supports(f.getType())) {
                                throw new IllegalArgumentException("@Service not supported on " + f);
                            }
                            if (!f.isAccessible()) {
                                f.setAccessible(true);
                            }
                            try {
                                f.set(testInstance, findInjection(context, f.getType()));
                            } catch (final IllegalAccessException e) {
                                throw new IllegalStateException(e);
                            }
                        });
                testClass = testClass.getSuperclass();
            }
        }

        private boolean supports(final Class<?> type) {
            return type == ContextualFramework.class;
        }

        private <T> T findInjection(final ExtensionContext extensionContext, final Class<T> type) {
            return extensionContext.getStore(NAMESPACE).get(type, type);
        }


        private static class Context implements AutoCloseable {
            private final Thread thread;
            private final ClassLoader previousLoader;
            private final URLClassLoader currentLoader;

            private Context(final Thread thread, final ClassLoader previousLoader, final URLClassLoader currentLoader) {
                this.thread = thread;
                this.previousLoader = previousLoader;
                this.currentLoader = currentLoader;
            }

            @Override
            public void close() {
                thread.setContextClassLoader(previousLoader);
                try {
                    currentLoader.close();
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        private static class FileToDelete {
            private final File file;

            private FileToDelete(final File file) {
                this.file = file;
            }
        }
    }
}