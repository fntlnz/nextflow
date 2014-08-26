/*
 * Copyright (c) 2013-2014, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2014, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.file
import java.lang.reflect.Field
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern

import embed.com.google.common.hash.HashCode
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.extension.FilesExtensions
import nextflow.extension.NextflowExtensions
import nextflow.util.CacheHelper

/**
 * Provides some helper method handling files
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class FileHelper {

    static final Pattern GLOB_FILE_BRACKETS = Pattern.compile(/(.*)(\{.+,.+\})(.*)/)

    static private Random rndGen = new Random()

    static final char[] ALPHA = ('a'..'z') as char[]

    static final char[] NUMERIC = ('0'..'9') as char[]

    static final char[] ALPHANUM = (('a'..'z')+('0'..'9')) as char[]


    static String normalizePath( String path ) {

        if( path == '~' ) {
            path = System.properties['user.home']
        }
        else if ( path.startsWith('~/') ) {
            path = System.properties['user.home'].toString() + path[1..-1]
        }


        return path
    }


    /**
     * Creates a random string with the number of character specified
     * e.g. {@code s8hm2nxt3}
     *
     * @param len The len of the final random string
     * @param alphabet The set of characters allowed in the random string
     * @return The generated random string
     */
    static String randomString( int len, char[] alphabet ) {
        assert alphabet.size() > 0

        StringBuilder result = new StringBuilder()
        final max = alphabet.size()

        len.times {
            def index = rndGen.nextInt(max)
            result.append( alphabet[index] )
        }

        return result.toString()
    }

    static String randomString( int len ) {
        randomString(len, ALPHA)
    }


    /**
     * The process scratch folder
     * @param seed
     * @return
     */
    @Deprecated
    static File createTempDir( final File baseDir = null ) {

        long timestamp = System.currentTimeMillis()
        while( true ) {

            String rnd1 = randomString(2, NUMERIC)
            String rnd2 = randomString(4, ALPHANUM)
            String rnd3 = randomString(4, ALPHANUM)
            String path = "$rnd1/$rnd2-$rnd3"

            File tempDir = baseDir ? new File(baseDir,path) : new File(path)

            if (tempDir.mkdirs()) {
                return tempDir.absoluteFile;
            }
            else if ( !tempDir.exists() ) {
                // when 'mkdirs' failed because it was unable to create the folder
                // (since it does not exist) throw an exception
                throw new IllegalStateException("Cannot create scratch folder: '${tempDir}' -- verify access permissions" )
            }

            if( System.currentTimeMillis() - timestamp > 1_000 ) {
                throw new IllegalStateException("Unable to create scratch folder: '${tempDir}' after multiple attempts -- verify access permissions" )
            }

            Thread.sleep(50)
        }
    }



    def static List nameParts( String name ) {
        assert name

        if( name.isLong() )  {
            return ['', name.toLong()]
        }

        def matcher = name =~ /^(\S*)?(\D)(\d+)$/
        if( matcher.matches() ) {
            def entries = (String[])matcher[0]
            return [ entries[1] + entries[2], entries[3].toString().toInteger() ]
        }
        else {
            return [name,0]
        }

    }

    /**
     * Check whenever a file or a directory is empty
     *
     * @param file The file path to
     */
    def static boolean empty( File file ) {
        assert file
        empty(file.toPath())
    }

    def static boolean empty( Path path ) {

        def attrs
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class)
        }
        catch (IOException e) {
            return true;
        }

        if ( attrs.isDirectory() ) {
            def stream = Files.newDirectoryStream(path)
            try {
                Iterator<Path> itr = stream.iterator()
                return !itr.hasNext()
            }
            finally {
                stream.close()
            }
        }
        else {
            return attrs.size() == 0
        }

    }

    /**
     * Defines a cacheable path for the specified {@code HashCode} instance.
     *
     * @param hash
     * @return
     */
    final static Path getWorkFolder(Path bashPath, HashCode hash) {
        assert bashPath
        assert hash

        def str = hash.toString()
        def bucket = str.substring(0,2)
        def folder = bashPath.resolve("${bucket}/${str.substring(2)}")

        return folder.toAbsolutePath()
    }

    final static Path createTempFolder(Path basePath) {
        assert basePath

        int count = 0
        while( true ) {
            def hash = CacheHelper.hasher(rndGen.nextLong()).hash().toString()
            def bucket = hash.substring(0,2)
            def result = basePath.resolve( "tmp/$bucket/${hash.substring(2)}" )

            if( FilesExtensions.exists(result) ) {
                if( ++count > 100 ) { throw new IOException("Unable to create a unique temporary path: $result") }
                continue
            }
            if( !FilesExtensions.mkdirs(result) ) {
                throw new IOException("Unable to create temporary parth: $result -- Verify file system access permission")
            }

            return result.toAbsolutePath()
        }

    }

    static Path asPath( String str ) {
        assert str

        int p = str.indexOf('://')
        if( p == -1 ) {
            return FileSystems.getDefault().getPath(str)
        }

        final uri = URI.create(str)
        if( uri.scheme == 'file' )
            return FileSystems.getDefault().getPath(uri.path)

        getOrCreateFileSystemFor(uri).provider().getPath(uri)
    }

    /**
     * Lazy create and memoize this object since it will never change
     * @return A map holding the current session
     */
    @Memoized
    static protected Map getEnvMap(String scheme) {
        getEnvMap0(scheme, System.getProperties(), System.getenv())
    }

    @PackageScope
    static Map getEnvMap0(String scheme, Properties props, Map env) {
        def result = [:]
        if( scheme?.toLowerCase() == 's3' ) {
            def accessKey = props.getProperty('AWS_ACCESS_KEY') ?: env.get('AWS_ACCESS_KEY')
            def secretKey = props.getProperty('AWS_SECRET_KEY') ?: env.get('AWS_SECRET_KEY')
            if( accessKey && secretKey ) {
                // S3FS expect the access - secret keys pair in lower notation
                result.access_key = accessKey
                result.secret_key = secretKey
            }
        }
        else {
            assert Session.currentInstance, "Session is not available -- make sure to call this after Session object has been created"
            result.session = Session.currentInstance
        }
        return result
    }


    /**
     *  Caches File system providers
     */
    @PackageScope
    static final Map<String, FileSystemProvider> providersMap = [:]

    /**
     * Returns a {@link FileSystemProvider} for a file scheme
     * @param scheme A file system scheme e.g. {@code file}, {@code s3}, {@code dxfs}, etc.
     * @return A {@link FileSystemProvider} instance for the specified scheme
     */
    static FileSystemProvider getProviderFor( String scheme ) {

        if( providersMap.containsKey(scheme) )
            return providersMap[scheme]

        synchronized (providersMap) {
            if( providersMap.containsKey(scheme) )
                return providersMap[scheme]

            for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
                if ( scheme == provider.getScheme() ) {
                    return providersMap[scheme] = provider;
                }
            }

            return null;
        }

    }

    /**
     * Get the instance of the specified {@code FileSystemProvider} class. If the provider is not
     * in the list of installed provided, it creates a new instance and add it to the list
     * <p>
     * This method has been deprecated use {@link #getOrCreateFileSystemFor(java.lang.String)} instead
     *
     * @see {@code FileSystemProvider#installedProviders}
     *
     * @param clazz A class extending {@code FileSystemProvider}
     * @return An instance of the specified class
     */
    @Deprecated
    synchronized static <T extends FileSystemProvider> T getOrInstallProvider( Class<T> clazz ) {

        FileSystemProvider result = FileSystemProvider.installedProviders().find { it.class == clazz }
        if( result )
            return (T)result

        // try to load DnaNexus file system provider dynamically
        result = (T)clazz.newInstance()

        // add it manually
        Field field = FileSystemProvider.class.getDeclaredField('installedProviders')
        field.setAccessible(true)
        List installedProviders = new ArrayList((List)field.get(null))
        installedProviders.add( result )
        field.set(this, Collections.unmodifiableList(installedProviders))
        log.debug "> Added '${clazz.simpleName}' to list of installed providers [${result.scheme}]"
        return (T)result
    }

    private static Lock _fs_lock = new ReentrantLock()

    /**
     * Acquire or create the file system for the given {@link URI}
     *
     * @param uri A {@link URI} locating a file into a file system
     * @param env An option environment specification that may be used to instantiate the underlying file system.
     *          As defined by {@link FileSystemProvider#newFileSystem(java.net.URI, java.util.Map)}
     * @return The corresponding {@link FileSystem} object
     * @throws IllegalArgumentException if does not exist a valid provider for the given URI scheme
     */
    static FileSystem getOrCreateFileSystemFor( URI uri, Map env = null ) {
        assert uri

        /*
         * get the provider for the specified URI
         */
        def provider = getProviderFor(uri.scheme)
        if( !provider )
            throw new IllegalArgumentException("Cannot a find a file system provider for scheme: ${uri.scheme}")

        /*
         * check if already exists a file system for it
         */
        FileSystem fs
        try { fs = provider.getFileSystem(uri) }
        catch( FileSystemNotFoundException e ) { fs=null }
        if( fs )
            return fs

        /*
         * since the file system does not exist, create it a protected block
         */
        log.debug "Creating a file system instance for provider: ${provider.class.simpleName}"
        NextflowExtensions.withLock(_fs_lock) {

            try { fs = provider.getFileSystem(uri) }
            catch( FileSystemNotFoundException e ) { fs=null }
            if( !fs ) {
                fs = provider.newFileSystem(uri, env ?: getEnvMap(uri.scheme))
            }
            fs
        }

        return fs
    }

    static FileSystem getOrCreateFileSystemFor( String scheme, Map env = null ) {
        getOrCreateFileSystemFor(URI.create("$scheme:///"), env)
    }

    /**
     * Given a path look for an existing file with that name in the {@code cacheDir} folder.
     * If exists will return the path to it, otherwise make a copy of it in the cache folder
     * and returns to the path to it.
     *
     * @param sourcePath A {@link Path} object to an existing file or directory
     * @param cacheDir A {@link Path} to the folder containing the cached objects
     * @param sessionId An option {@link UUID} used to create the object invalidation key
     * @return A {@link Path} to the cached version of the specified object
     */
    static Path getLocalCachePath( Path sourcePath, Path cacheDir, UUID sessionId = null ) {
        assert sourcePath
        assert cacheDir

        final key = sessionId ? [sessionId, sourcePath] : sourcePath
        final hash = CacheHelper.hasher(key).hash()

        final cached = getWorkFolder(cacheDir, hash).resolve(sourcePath.getFileName().toString())
        if( Files.exists(cached) )
            return cached

        synchronized (cacheDir)  {
            if( Files.exists(cached) )
                return cached

            return FilesExtensions.copyTo(sourcePath, cached)
        }
    }

    /**
     * Whenever the specified string is a glob file pattern
     *
     * @link  http://docs.oracle.com/javase/tutorial/essential/io/fileOps.html#glob
     *
     * @param filePattern
     * @return
     */
    static isGlobPattern( String filePattern ) {
        assert filePattern
        boolean glob  = false
        glob |= filePattern.contains('*')
        glob |= filePattern.contains('?')
        return glob || GLOB_FILE_BRACKETS.matcher(filePattern).matches()
    }

    /**
     * Returns a {@code PathMatcher} that performs match operations on the
     * {@code String} representation of {@link Path} objects by interpreting a
     * given pattern.
     *
     * @see FileSystem#getPathMatcher(java.lang.String)
     *
     * @param syntaxAndInput
     * @return
     */
    static PathMatcher getDefaultPathMatcher(String syntaxAndInput) {

        int pos = syntaxAndInput.indexOf(':');
        if (pos <= 0 || pos == syntaxAndInput.length())
            throw new IllegalArgumentException();

        String syntax = syntaxAndInput.substring(0, pos);
        String input = syntaxAndInput.substring(pos+1);

        String expr;
        if (syntax == 'glob') {
            expr = Globs.toUnixRegexPattern(input);
        }
        else if (syntax == 'regex' ) {
            expr = input;
        }
        else {
            throw new UnsupportedOperationException("Syntax '$syntax' not recognized");
        }

        // return matcher
        final Pattern pattern = Pattern.compile(expr);
        return new PathMatcher() {
            @Override
            public boolean matches(Path path) {
                return pattern.matcher(path.toString()).matches();
            }
        };
    }

    /**
     * Get a path matcher for the specified file system and file pattern.
     *
     * It tries to get the matcher returned by {@link FileSystem#getPathMatcher} and
     * falling back to {@link #getDefaultPathMatcher(java.lang.String)} when it return a null or an {@link UnsupportedOperationException}
     *
     * @param fileSystem
     * @param syntaxAndPattern
     * @return A {@link PathMatcher} instance for the specified file pattern
     */
    static PathMatcher getPathMatcherFor(String syntaxAndPattern, FileSystem fileSystem ) {
        assert fileSystem
        assert syntaxAndPattern

        PathMatcher matcher
        try {
            matcher = fileSystem.getPathMatcher(syntaxAndPattern)
        }
        catch( UnsupportedOperationException e ) {
            matcher = null
        }

        if( !matcher ) {
            log.debug "Path matcher not defined by '${fileSystem.class.simpleName}' file system -- using default default strategy"
            matcher = getDefaultPathMatcher(syntaxAndPattern)
        }

        return matcher
    }


    /**
     * Applies the specified action on one or more files and directories matching the specified glob pattern
     * @param folder
     * @param filePattern
     * @param action
     */
    static void visitFiles( Map options = null, Path folder, String filePattern, Closure action ) {
        assert folder
        assert filePattern
        assert action

        def matcher = getPathMatcherFor("glob:${folder.resolve(filePattern)}", folder.fileSystem)
        def singleParam = action.getMaximumNumberOfParameters() == 1
        def relative = options?.relative == true

        Files.walkFileTree(folder, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                log.trace "visitFile: $path "
                if( matcher.matches(path) ) {
                    def result = relative ? folder.relativize(path) : path
                    singleParam ? action.call(result) : action.call(result,attrs)
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                log.trace "visitDir : $path"
                if( matcher.matches(path) ) {
                    def result = relative ? folder.relativize(path) : path
                    singleParam ? action.call(result) : action.call(result,attrs)
                }
                return FileVisitResult.CONTINUE;
            }

        })

    }


}
