package org.apache.maven.archetype.generator;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.archetype.common.ArchetypeArtifactManager;
import org.apache.maven.archetype.common.ArchetypeFilesResolver;
import org.apache.maven.archetype.common.Constants;
import org.apache.maven.archetype.common.PomManager;
import org.apache.maven.archetype.exception.ArchetypeGenerationFailure;
import org.apache.maven.archetype.exception.ArchetypeNotConfigured;
import org.apache.maven.archetype.exception.InvalidPackaging;
import org.apache.maven.archetype.exception.OutputFileExists;
import org.apache.maven.archetype.exception.PomFileExists;
import org.apache.maven.archetype.exception.ProjectDirectoryExists;
import org.apache.maven.archetype.exception.UnknownArchetype;
import org.apache.maven.archetype.metadata.AbstractArchetypeDescriptor;
import org.apache.maven.archetype.metadata.ArchetypeDescriptor;
import org.apache.maven.archetype.metadata.FileSet;
import org.apache.maven.archetype.metadata.ModuleDescriptor;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.context.Context;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.codehaus.plexus.velocity.VelocityComponent;
import org.dom4j.DocumentException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.archetype.ArchetypeGenerationRequest;
import org.apache.maven.archetype.metadata.RequiredProperty;

/** @plexus.component */
public class DefaultFilesetArchetypeGenerator
    extends AbstractLogEnabled
    implements FilesetArchetypeGenerator
{
    /** @plexus.requirement */
    private ArchetypeArtifactManager archetypeArtifactManager;

    /** @plexus.requirement */
    private ArchetypeFilesResolver archetypeFilesResolver;

    /** @plexus.requirement */
    private PomManager pomManager;

    /** @plexus.requirement */
    private VelocityComponent velocity;

    /**
     * Token delimiter.
     */
    private static final String DELIMITER = "__";

    /**
     * Pattern used to detect tokens in a string. Tokens are any text surrounded
     * by the delimiter.
     */
    private static final Pattern TOKEN_PATTERN = Pattern.compile( ".*" + DELIMITER + ".*" + DELIMITER + ".*" );

    public void generateArchetype( ArchetypeGenerationRequest request, File archetypeFile )
        throws UnknownArchetype, ArchetypeNotConfigured, ProjectDirectoryExists, PomFileExists, OutputFileExists,
        ArchetypeGenerationFailure
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();

        try
        {
            ArchetypeDescriptor archetypeDescriptor =
                archetypeArtifactManager.getFileSetArchetypeDescriptor( archetypeFile );

            if ( !isArchetypeConfigured( archetypeDescriptor, request ) )
            {
                if ( request.isInteractiveMode () )
                {
                    throw new ArchetypeNotConfigured ( "No archetype was chosen.", null );
                }
                else
                {
                    StringBuffer exceptionMessage = new StringBuffer();
                    exceptionMessage.append( "Archetype " + request.getArchetypeGroupId() + ":"
                        + request.getArchetypeArtifactId() + ":" + request.getArchetypeVersion()
                        + " is not configured" );

                    List<String> missingProperties = new ArrayList<String>( 0 );
                    for ( RequiredProperty requiredProperty : archetypeDescriptor.getRequiredProperties() )
                    {
                        if ( StringUtils.isEmpty( request.getProperties().getProperty( requiredProperty.getKey() ) ) )
                        {
                            exceptionMessage.append( "\n\tProperty " + requiredProperty.getKey() + " is missing." );

                            missingProperties.add( requiredProperty.getKey() );
                        }
                    }

                    throw new ArchetypeNotConfigured( exceptionMessage.toString(), missingProperties );
                }
            }

            Context context = prepareVelocityContext( request );

            String packageName = request.getPackage();
            String artifactId = request.getArtifactId();
            File outputDirectoryFile = new File( request.getOutputDirectory(), artifactId );
            File basedirPom = new File( request.getOutputDirectory(), Constants.ARCHETYPE_POM );
            File pom = new File( outputDirectoryFile, Constants.ARCHETYPE_POM );

            List<String> archetypeResources =
                archetypeArtifactManager.getFilesetArchetypeResources( archetypeFile );

            ZipFile archetypeZipFile =
                archetypeArtifactManager.getArchetypeZipFile( archetypeFile );

            ClassLoader archetypeJarLoader =
                archetypeArtifactManager.getArchetypeJarLoader( archetypeFile );

            Thread.currentThread().setContextClassLoader( archetypeJarLoader );

            if ( archetypeDescriptor.isPartial() )
            {
                getLogger().debug( "Processing partial archetype " + archetypeDescriptor.getName() );
                if ( outputDirectoryFile.exists() )
                {
                    if ( !pom.exists() )
                    {
                        throw new PomFileExists( "This is a partial archetype and the pom.xml file doesn't exist." );
                    }
                    else
                    {
                        processPomWithMerge( context, pom, "" );

                        processArchetypeTemplatesWithWarning( archetypeDescriptor, archetypeResources,
                                                              archetypeZipFile, "", context, packageName,
                                                              outputDirectoryFile );
                    }
                }
                else
                {
                    if ( basedirPom.exists() )
                    {
                        processPomWithMerge( context, basedirPom, "" );

                        processArchetypeTemplatesWithWarning( archetypeDescriptor, archetypeResources,
                                                              archetypeZipFile, "", context, packageName,
                                                              new File( request.getOutputDirectory() ) );
                    }
                    else
                    {
                        processPom( context, pom, "" );

                        processArchetypeTemplates( archetypeDescriptor, archetypeResources, archetypeZipFile, "",
                                                   context, packageName, outputDirectoryFile );
                    }
                }

                if ( archetypeDescriptor.getModules().size() > 0 )
                {
                    getLogger().info( "Modules ignored in partial mode" );
                }
            }
            else
            {
                getLogger().debug( "Processing complete archetype " + archetypeDescriptor.getName() );
                if ( outputDirectoryFile.exists() && pom.exists() )
                {
                    throw new ProjectDirectoryExists( "A Maven 2 project already exists in the directory "
                        + outputDirectoryFile.getPath() );
                }
                else
                {
                    if ( outputDirectoryFile.exists() )
                    {
                        getLogger().warn( "The directory " + outputDirectoryFile.getPath() + " already exists." );
                    }

                    context.put( "rootArtifactId", artifactId );

                    processFilesetModule( artifactId, artifactId, archetypeResources, pom, archetypeZipFile, "",
                                          basedirPom, outputDirectoryFile, packageName, archetypeDescriptor, context );
                }
            }
        }
        catch ( FileNotFoundException ex )
        {
            throw new ArchetypeGenerationFailure( ex );
        }
        catch ( IOException ex )
        {
            throw new ArchetypeGenerationFailure( ex );
        }
        catch ( XmlPullParserException ex )
        {
            throw new ArchetypeGenerationFailure( ex );
        }
        catch ( DocumentException ex )
        {
            throw new ArchetypeGenerationFailure( ex );
        }
        catch ( ArchetypeGenerationFailure ex )
        {
            throw new ArchetypeGenerationFailure( ex );
        }
        catch ( InvalidPackaging ex )
        {
            throw new ArchetypeGenerationFailure( ex );
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( old );
        }
    }

    public String getPackageAsDirectory( String packageName )
    {
        return StringUtils.replace( packageName, ".", "/" );
    }

    private void copyFile( final File outFile, final String template, final boolean failIfExists,
                           final ZipFile archetypeZipFile )
        throws FileNotFoundException, OutputFileExists, IOException
    {
        getLogger().debug( "Copying file " + template );

        if ( failIfExists && outFile.exists() )
        {
            throw new OutputFileExists( "Don't rewrite file " + outFile.getName() );
        }
        else if ( outFile.exists() )
        {
            getLogger().warn( "CP Don't override file " + outFile );
        }
        else
        {
            ZipEntry input =
                archetypeZipFile.getEntry( Constants.ARCHETYPE_RESOURCES + "/" + template );

            InputStream inputStream = null;
            OutputStream out = null;
            try
            {
                inputStream = archetypeZipFile.getInputStream( input );

                outFile.getParentFile().mkdirs();

                out = new FileOutputStream( outFile );

                IOUtil.copy( inputStream, out );
            }
            finally
            {
                IOUtil.close( inputStream );
                IOUtil.close( out );
            }
        }
    }

    private void copyFiles( String directory, List<String> fileSetResources, boolean packaged, String packageName,
                            File outputDirectoryFile, ZipFile archetypeZipFile, String moduleOffset,
                            boolean failIfExists, Context context )
        throws OutputFileExists, FileNotFoundException, IOException
    {
        for ( String template : fileSetResources )
        {
            File outputFile =
                getOutputFile( template, directory, outputDirectoryFile, packaged, packageName, moduleOffset, context );

            copyFile( outputFile, template, failIfExists, archetypeZipFile );
        }
    }

    private String getEncoding( String archetypeEncoding )
    {
        return StringUtils.isEmpty( archetypeEncoding ) ? "UTF-8" : archetypeEncoding;
    }

    private String getOffsetSeparator( String moduleOffset )
    {
        return StringUtils.isEmpty( moduleOffset ) ? "/" : ( "/" + moduleOffset + "/" );
    }

    private File getOutputFile( String template, String directory, File outputDirectoryFile, boolean packaged,
                                String packageName, String moduleOffset, Context context )
    {
        String templateName = StringUtils.replaceOnce( template, directory, "" );

        String outputFileName =
            directory + "/" + ( packaged ? getPackageAsDirectory( packageName ) : "" ) + "/"
                + templateName.substring( moduleOffset.length() );

        if ( TOKEN_PATTERN.matcher( outputFileName ).matches() )
        {
            outputFileName = replaceFilenameTokens( outputFileName, context );
        }

        File outputFile = new File( outputDirectoryFile, outputFileName );

        return outputFile;
    }

    /**
     * Replaces all tokens (text surrounded by the {@link #DELIMITER}) within
     * the given string, using properties contained within the context. If a
     * property does not exist in the context, the token is left unmodified
     * and a warning is logged.
     *
     * @param filePath the file name and path to be interpolated
     * @param context contains the available properties
     */
    private String replaceFilenameTokens( String filePath, Context context )
    {
        String interpolatedResult = filePath;
        String propertyToken = null;
        String contextPropertyValue = null;

        int start = 0;
        int end = 0;
        int skipUndefinedPropertyIndex = 0;

        int maxAttempts = StringUtils.countMatches( interpolatedResult, DELIMITER ) / 2;

        for ( int x = 0; x < maxAttempts && start != -1; x++ )
        {
            start = interpolatedResult.indexOf( DELIMITER, skipUndefinedPropertyIndex );

            if ( start != -1 )
            {
                end = interpolatedResult.indexOf( DELIMITER, start + DELIMITER.length() );

                if ( end != -1 )
                {
                    propertyToken = interpolatedResult.substring( start + DELIMITER.length(), end );
                }

                contextPropertyValue = (String) context.get( propertyToken );

                if ( contextPropertyValue != null && contextPropertyValue.trim().length() > 0 )
                {
                    if ( getLogger().isDebugEnabled() )
                    {
                        getLogger().debug(
                                           "Replacing '" + DELIMITER + propertyToken + DELIMITER + "' in file path '"
                                               + interpolatedResult + "' with value '" + contextPropertyValue + "'." );
                    }

                    interpolatedResult =
                        StringUtils.replace( interpolatedResult, DELIMITER + propertyToken + DELIMITER,
                                             contextPropertyValue );

                }
                else
                {
                    // Need to skip the undefined property
                    skipUndefinedPropertyIndex = end + DELIMITER.length() + 1;

                    getLogger().warn(
                                      "Property '" + propertyToken + "' was not specified, so the token in '"
                                          + interpolatedResult + "' is not being replaced." );
                }
            }
        }

        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "Final interpolated file path: '" + interpolatedResult + "'" );
        }

        return interpolatedResult;
    }

    private String getPackageInPathFormat( String aPackage )
    {
        return StringUtils.replace( aPackage, ".", "/" );
    }

    private boolean isArchetypeConfigured( ArchetypeDescriptor archetypeDescriptor, ArchetypeGenerationRequest request )
    {
        for ( RequiredProperty requiredProperty : archetypeDescriptor.getRequiredProperties() )
        {
            if ( StringUtils.isEmpty( request.getProperties().getProperty( requiredProperty.getKey() ) ) )
            {
                return false;
            }
        }

        return true;
    }

    private void setParentArtifactId( Context context, String artifactId )
    {
        context.put( Constants.PARENT_ARTIFACT_ID, artifactId );
    }

    private Context prepareVelocityContext( ArchetypeGenerationRequest request )
    {
        Context context = new VelocityContext();
        context.put( Constants.GROUP_ID, request.getGroupId() );
        context.put( Constants.ARTIFACT_ID, request.getArtifactId() );
        context.put( Constants.VERSION, request.getVersion() );
        context.put( Constants.PACKAGE, request.getPackage() );
        context.put( Constants.PACKAGE_IN_PATH_FORMAT, getPackageInPathFormat( request.getPackage() ) );

        for ( Iterator iterator = request.getProperties().keySet().iterator(); iterator.hasNext(); )
        {
            String key = (String) iterator.next();

            Object value = request.getProperties().getProperty( key );

            context.put( key, value );
        }
        return context;
    }

    private void processArchetypeTemplates( AbstractArchetypeDescriptor archetypeDescriptor,
                                            List<String> archetypeResources, ZipFile archetypeZipFile,
                                            String moduleOffset, Context context, String packageName,
                                            File outputDirectoryFile )
        throws OutputFileExists, ArchetypeGenerationFailure, FileNotFoundException, IOException
    {
        processTemplates( packageName, outputDirectoryFile, context, archetypeDescriptor, archetypeResources,
                          archetypeZipFile, moduleOffset, false );
    }

    private void processArchetypeTemplatesWithWarning(
                                                       org.apache.maven.archetype.metadata.ArchetypeDescriptor archetypeDescriptor,
                                                       List<String> archetypeResources, ZipFile archetypeZipFile,
                                                       String moduleOffset, Context context, String packageName,
                                                       File outputDirectoryFile )
        throws OutputFileExists, ArchetypeGenerationFailure, FileNotFoundException, IOException
    {
        processTemplates( packageName, outputDirectoryFile, context, archetypeDescriptor, archetypeResources,
                          archetypeZipFile, moduleOffset, true );
    }

    private void processFileSet( String directory, List<String> fileSetResources, boolean packaged, String packageName,
                                 Context context, File outputDirectoryFile, String moduleOffset,
                                 String archetypeEncoding, boolean failIfExists )
        throws OutputFileExists, ArchetypeGenerationFailure
    {
        for ( String template : fileSetResources )
        {
            File outputFile =
                getOutputFile( template, directory, outputDirectoryFile, packaged, packageName, moduleOffset, context );

            processTemplate( outputFile, context, Constants.ARCHETYPE_RESOURCES + "/" + template, archetypeEncoding,
                             failIfExists );
        }
    }

    private void processFilesetModule( String rootArtifactId, String artifactId, final List<String> archetypeResources,
                                       File pom, final ZipFile archetypeZipFile, String moduleOffset, File basedirPom,
                                       File outputDirectoryFile, final String packageName,
                                       final AbstractArchetypeDescriptor archetypeDescriptor, final Context context )
        throws DocumentException, XmlPullParserException, ArchetypeGenerationFailure, InvalidPackaging, IOException,
        OutputFileExists
    {
        outputDirectoryFile.mkdirs();
        getLogger().debug( "Processing module " + artifactId );
        getLogger().debug( "Processing module rootArtifactId " + rootArtifactId );
        getLogger().debug( "Processing module pom " + pom );
        getLogger().debug( "Processing module moduleOffset " + moduleOffset );
        getLogger().debug( "Processing module outputDirectoryFile " + outputDirectoryFile );

        processFilesetProject( archetypeDescriptor,
                               StringUtils.replace( artifactId, "${rootArtifactId}", rootArtifactId ),
                               archetypeResources, pom, archetypeZipFile, moduleOffset, context, packageName,
                               outputDirectoryFile, basedirPom );

        String parentArtifactId = (String) context.get( Constants.PARENT_ARTIFACT_ID );

        Iterator<ModuleDescriptor> subprojects = archetypeDescriptor.getModules().iterator();

        if ( subprojects.hasNext() )
        {
            getLogger().debug( artifactId + " has modules (" + archetypeDescriptor.getModules() + ")" );

            setParentArtifactId( context, StringUtils.replace( artifactId, "${rootArtifactId}", rootArtifactId ) );
        }

        while ( subprojects.hasNext() )
        {
            ModuleDescriptor project = subprojects.next();

            artifactId = project.getId();

            File moduleOutputDirectoryFile =
                new File( outputDirectoryFile,
                          StringUtils.replace( project.getDir(), "__rootArtifactId__", rootArtifactId ) );

            context.put( Constants.ARTIFACT_ID,
                         StringUtils.replace( project.getId(), "${rootArtifactId}", rootArtifactId ) );

            processFilesetModule( rootArtifactId,
                                  StringUtils.replace( project.getDir(), "__rootArtifactId__", rootArtifactId ),
                                  archetypeResources,
                                  new File( moduleOutputDirectoryFile, Constants.ARCHETYPE_POM ), archetypeZipFile,
                                  ( StringUtils.isEmpty( moduleOffset ) ? "" : ( moduleOffset + "/" ) )
                                      + StringUtils.replace( project.getDir(), "${rootArtifactId}", rootArtifactId ),
                                  pom, moduleOutputDirectoryFile, packageName, project, context );
        }

        restoreParentArtifactId( context, parentArtifactId );

        getLogger().debug( "Processed " + artifactId );
    }

    private void processFilesetProject( final AbstractArchetypeDescriptor archetypeDescriptor, final String moduleId,
                                        final List<String> archetypeResources, final File pom,
                                        final ZipFile archetypeZipFile, String moduleOffset, final Context context,
                                        final String packageName, final File outputDirectoryFile, final File basedirPom )
        throws DocumentException, XmlPullParserException, ArchetypeGenerationFailure, InvalidPackaging, IOException,
        FileNotFoundException, OutputFileExists
    {
        getLogger().debug( "Processing fileset project moduleId " + moduleId );
        getLogger().debug( "Processing fileset project pom " + pom );
        getLogger().debug( "Processing fileset project moduleOffset " + moduleOffset );
        getLogger().debug( "Processing fileset project outputDirectoryFile " + outputDirectoryFile );
        getLogger().debug( "Processing fileset project basedirPom " + basedirPom );

        if ( basedirPom.exists() )
        {
            processPomWithParent( context, pom, moduleOffset, basedirPom, moduleId );
        }
        else
        {
            processPom( context, pom, moduleOffset );
        }

        processArchetypeTemplates( archetypeDescriptor, archetypeResources, archetypeZipFile, moduleOffset, context,
                                   packageName, outputDirectoryFile );
    }

    private void processPom( Context context, File pom, String moduleOffset )
        throws OutputFileExists, ArchetypeGenerationFailure
    {
        getLogger().debug( "Processing pom " + pom );

        processTemplate( pom, context, Constants.ARCHETYPE_RESOURCES + getOffsetSeparator( moduleOffset )
            + Constants.ARCHETYPE_POM, getEncoding( null ), true );
    }

    private void processPomWithMerge( Context context, File pom, String moduleOffset )
        throws OutputFileExists, IOException, XmlPullParserException, ArchetypeGenerationFailure
    {
        getLogger().debug( "Processing pom " + pom + " with merge" );

        File temporaryPom = getTemporaryFile( pom );

        processTemplate( temporaryPom, context, Constants.ARCHETYPE_RESOURCES + getOffsetSeparator( moduleOffset )
            + Constants.ARCHETYPE_POM, getEncoding( null ), true );

        pomManager.mergePoms( pom, temporaryPom );

        // getTemporaryFile sets deleteOnExit. Lets try to delete and then make sure deleteOnExit is
        // still set. Windows has issues deleting files with certain JDKs.
        try
        {
            FileUtils.forceDelete( temporaryPom );
        }
        catch ( IOException e )
        {
            temporaryPom.deleteOnExit();
        }
    }

    private void processPomWithParent( Context context, File pom, String moduleOffset, File basedirPom, String moduleId )
        throws OutputFileExists, XmlPullParserException, DocumentException, IOException, InvalidPackaging,
        ArchetypeGenerationFailure
    {
        getLogger().debug( "Processing pom " + pom + " with parent " + basedirPom );

        processTemplate( pom, context, Constants.ARCHETYPE_RESOURCES + getOffsetSeparator( moduleOffset )
            + Constants.ARCHETYPE_POM, getEncoding( null ), true );

        getLogger().debug( "Adding module " + moduleId );

        pomManager.addModule( basedirPom, moduleId );

        pomManager.addParent( pom, basedirPom );
    }

    private void processTemplate( File outFile, Context context, String templateFileName, String encoding,
                                  boolean failIfExists )
        throws OutputFileExists, ArchetypeGenerationFailure
    {
        templateFileName = templateFileName.replace( File.separatorChar, '/' );

        String localTemplateFileName = templateFileName.replace( '/', File.separatorChar );
        if ( !templateFileName.equals( localTemplateFileName )
            && !velocity.getEngine().templateExists( templateFileName )
            && velocity.getEngine().templateExists( localTemplateFileName ) )
        {
            templateFileName = localTemplateFileName;
        }

        getLogger().debug( "Processing template " + templateFileName );

        if ( outFile.exists() )
        {
            if ( failIfExists )
            {
                throw new OutputFileExists( "Don't override file " + outFile.getAbsolutePath() );
            }
            else
            {
                getLogger().warn( "Don't override file " + outFile );
            }
        }
        else
        {
            if ( !outFile.getParentFile().exists() )
            {
                outFile.getParentFile().mkdirs();
            }

            getLogger().debug( "Merging into " + outFile );

            Writer writer = null;

            try
            {
                StringWriter stringWriter = new StringWriter();

                velocity.getEngine().mergeTemplate( templateFileName, encoding, context, stringWriter );

                writer = new OutputStreamWriter( new FileOutputStream( outFile ), encoding );

                writer.write( StringUtils.unifyLineSeparators( stringWriter.toString() ) );

                writer.flush();
            }
            catch ( Exception e )
            {
                throw new ArchetypeGenerationFailure( "Error merging velocity templates: " + e.getMessage(), e );
            }
            finally
            {
                IOUtil.close( writer );
            }
        }
    }

    private void processTemplates( String packageName, File outputDirectoryFile, Context context,
                                   AbstractArchetypeDescriptor archetypeDescriptor, List<String> archetypeResources,
                                   ZipFile archetypeZipFile, String moduleOffset, boolean failIfExists )
        throws OutputFileExists, ArchetypeGenerationFailure, FileNotFoundException, IOException
    {
        Iterator<FileSet> iterator = archetypeDescriptor.getFileSets().iterator();
        if ( iterator.hasNext() )
        {
            getLogger().debug( "Processing filesets" );
        }

        while ( iterator.hasNext() )
        {
            FileSet fileSet = iterator.next();

            List<String> fileSetResources =
                archetypeFilesResolver.filterFiles( moduleOffset, fileSet, archetypeResources );

            // This creates an empty directory, even if there is no file to process
            // Fix for ARCHETYPE-57
            getOutputFile( moduleOffset, fileSet.getDirectory(), outputDirectoryFile, fileSet.isPackaged(),
                           packageName, moduleOffset, context ).mkdirs();

            if ( fileSet.isFiltered() )
            {
                getLogger().debug(
                                   "Processing fileset " + fileSet + "\n\n\n\n" + fileSetResources + "\n\n"
                                       + archetypeResources + "\n\n" );

                processFileSet( fileSet.getDirectory(), fileSetResources, fileSet.isPackaged(), packageName, context,
                                outputDirectoryFile, moduleOffset, getEncoding( fileSet.getEncoding() ), failIfExists );

                getLogger().debug( "Processed " + fileSetResources.size() + " files." );
            }
            else
            {
                getLogger().debug( "Copying fileset " + fileSet );

                copyFiles( fileSet.getDirectory(), fileSetResources, fileSet.isPackaged(), packageName,
                           outputDirectoryFile, archetypeZipFile, moduleOffset, failIfExists, context );

                getLogger().debug( "Copied " + fileSetResources.size() + " files." );
            }
        }
    }

    private void restoreParentArtifactId( Context context, String parentArtifactId )
    {
        if ( StringUtils.isEmpty( parentArtifactId ) )
        {
            context.remove( Constants.PARENT_ARTIFACT_ID );
        }
        else
        {
            context.put( Constants.PARENT_ARTIFACT_ID, parentArtifactId );
        }
    }

    private File getTemporaryFile( File file )
    {
        File tmp = FileUtils.createTempFile( file.getName(), Constants.TMP, file.getParentFile() );

        tmp.deleteOnExit();

        return tmp;
    }
}
