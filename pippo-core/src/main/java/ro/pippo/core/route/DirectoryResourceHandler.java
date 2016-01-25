/*
 * Copyright (C) 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ro.pippo.core.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.pippo.core.HttpConstants;
import ro.pippo.core.PippoRuntimeException;
import ro.pippo.core.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Serves a directory as a resource.
 *
 * This is different than a FileResourceHandler because...
 *   1. it will display a static welcome file for directory requests
 *   2. it will render file lists (either by template or generated) if there is no welcome file
 *   3. it will properly respond to HEAD requests
 *
 * @author James Moger
 */
public class DirectoryResourceHandler implements RouteHandler {

    private static final Logger log = LoggerFactory.getLogger(DirectoryResourceHandler.class);

    public static final String PATH_PARAMETER = "path";

    private final String urlPath;

    private final String uriPattern;

    final File directory;

    private String timestampPattern = "yyyy-MM-dd HH:mm Z";

    private String fileSizePattern = "#,000";

    private String directoryTemplate;

    public DirectoryResourceHandler(String urlPath, File directory) {
        this.urlPath = urlPath;
        String normalizedPath = getNormalizedPath(urlPath);
        if (normalizedPath.length() > 0) {
            this.uriPattern = String.format("/%s/?{%s: .*}", getNormalizedPath(urlPath), PATH_PARAMETER);
        } else {
            this.uriPattern = String.format("/{%s: .*}", PATH_PARAMETER);
        }

        this.directory = directory.getAbsoluteFile();
    }

    public DirectoryResourceHandler(String urlPath, String directory) {
        this(urlPath, new File(directory));
    }

    public String getUrlPath() {
        return urlPath;
    }

    public String getUriPattern() {
        return uriPattern;
    }

    public String getTimestampPattern() {
        return timestampPattern;
    }

    public void setTimestampPattern(String pattern) {
        this.timestampPattern = pattern;
    }

    public String getFileSizePattern() {
        return fileSizePattern;
    }

    public void setFileSizePattern(String pattern) {
        this.fileSizePattern = pattern;
    }

    public String getDirectoryTemplate() {
        return directoryTemplate;
    }

    public void setDirectoryTemplate(String template) {
        this.directoryTemplate = template;
    }

    @Override
    public final void handle(RouteContext routeContext) {
        String resourcePath = getResourcePath(routeContext);
        log.trace("Request resource '{}'", resourcePath);

        handle(routeContext, resourcePath);

        routeContext.next();
    }

    protected void handle(RouteContext routeContext, String resourcePath) {
        try {
            Path requestedPath = new File(directory, resourcePath).toPath().normalize().toAbsolutePath();
            if (!requestedPath.startsWith(directory.getAbsolutePath())) {
                log.warn("Request for '{}' which is not located in '{}'", requestedPath, directory);
            } else if (StringUtils.isNullOrEmpty(resourcePath) || "/".equals(resourcePath)) {

                handleDirectoryRequest(routeContext, directory);

            } else {
                // look for requested file
                File file = requestedPath.toFile();
                if (file.exists()) {
                    if (file.isFile()) {
                        URL url = requestedPath.toUri().toURL();
                        switch (routeContext.getRequestMethod()) {
                            case HttpConstants.Method.HEAD:
                                setResponseHeaders(url, routeContext);
                                routeContext.getResponse().commit();
                                break;
                            case HttpConstants.Method.GET:
                                streamResource(url, routeContext);
                                break;
                            default:
                                log.warn("Unsupported request method {} for {}",
                                    routeContext.getRequestMethod(), routeContext.getRequestUri());
                        }
                    } else {
                        handleDirectoryRequest(routeContext, file);
                    }
                } else {
                    log.warn("{} not found for request path {}", requestedPath, routeContext.getRequestUri());
                }
            }
        } catch (MalformedURLException e) {
            log.error(e.getMessage(), e);
        }
    }

    protected File getIndexFile(File dir) {
        String[] welcomeFiles = new String[]{"index.html", "index.htm"};
        for (String welcomeFile : welcomeFiles) {
            File file = new File(dir, welcomeFile);
            if (file.exists()) {
                return file;
            }
        }

        return null;
    }

    protected void handleDirectoryRequest(RouteContext routeContext, File dir) throws MalformedURLException {
        File index = getIndexFile(dir);
        if (index != null) {
            URL url = index.toURI().toURL();
            streamResource(url, routeContext);
            return;
        }

        sendDirectoryListing(routeContext, dir);
    }

    protected File[] getFiles(File directory) {
        return Optional.ofNullable(directory.listFiles()).orElse(new File[0]);
    }

    protected List<DirEntry> getDirEntries(RouteContext routeContext, File dir, String absoluteDirUri) {
        List<DirEntry> list = new ArrayList<>();
        for (File file : getFiles(dir)) {
            String fileUrl = routeContext.getRequest().getApplicationPath()
                + StringUtils.removeEnd(StringUtils.addStart(absoluteDirUri, "/"), "/")
                + StringUtils.addStart(file.getName(), "/");
            list.add(new DirEntry(fileUrl, file));
        }
        Collections.sort(list);

        if (!directory.equals(dir)) {
            File upDir = new File(dir, "../");
            list.add(0, new DirEntry(routeContext.getRequest().getApplicationPath()
                + StringUtils.removeEnd(StringUtils.addStart(absoluteDirUri, "/"), "/")
                + StringUtils.addStart(upDir.getName(), "/"), upDir));
        }
        return list;
    }

    protected String getResourcePath(RouteContext routeContext) {
        return getNormalizedPath(routeContext.getParameter(PATH_PARAMETER).toString());
    }

    protected String getNormalizedPath(String path) {
        if (path.length() > 0 && '/' == path.charAt(0)) {
            path = path.substring(1);
        }
        if (path.length() > 0 && '/' == path.charAt(path.length() - 1)) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    protected void setResponseHeaders(URL resourceUrl, RouteContext routeContext) {
        try {
            long lastModified = resourceUrl.openConnection().getLastModified();
            routeContext.getApplication().getHttpCacheToolkit().addEtag(routeContext, lastModified);
        } catch (Exception e) {
            throw new PippoRuntimeException("Failed to stream resource {}", e, resourceUrl);
        }
    }

    protected void streamResource(URL resourceUrl, RouteContext routeContext) {
        try {
            setResponseHeaders(resourceUrl, routeContext);
            if (routeContext.getResponse().getStatus() == HttpConstants.StatusCode.NOT_MODIFIED) {
                // do not stream anything out, simply return 304
                routeContext.getResponse().commit();
            } else {
                sendResource(resourceUrl, routeContext);
            }
        } catch (Exception e) {
            throw new PippoRuntimeException("Failed to stream resource {}", e, resourceUrl);
        }
    }

    protected void sendResource(URL resourceUrl, RouteContext routeContext) throws IOException {
        String filename = resourceUrl.getFile();
        String mimeType = routeContext.getApplication().getMimeTypes().getContentType(filename);
        if (!StringUtils.isNullOrEmpty(mimeType)) {
            // stream the resource
            log.debug("Streaming as resource '{}'", resourceUrl);
            routeContext.getResponse().contentType(mimeType);
            routeContext.getResponse().ok().resource(resourceUrl.openStream());
        } else {
            // stream the file
            log.debug("Streaming as file '{}'", resourceUrl);
            routeContext.getResponse().ok().file(filename, resourceUrl.openStream());
        }
    }

    private void sendDirectoryListing(RouteContext routeContext, File dir) {
        String absoluteDirUri = getUrlPath() + StringUtils.addStart(directory.toPath().relativize(dir.toPath()).toString(), "/");
        if (StringUtils.isNullOrEmpty(directoryTemplate)) {
            // Generate primitive, default directory listing
            String page = generateDefaultDirectoryListing(routeContext, dir, absoluteDirUri);
            routeContext.html().send(page);
        } else {
            // Render directory listing template
            int numFiles = 0;
            int numDirs = 0;
            long diskUsage = 0;
            List<DirEntry> dirEntries = getDirEntries(routeContext, dir, absoluteDirUri);
            for (DirEntry dirEntry : dirEntries) {
                if (dirEntry.isFile()) {
                    numFiles++;
                    diskUsage += dirEntry.getSize();
                } else if (dirEntry.isDirectory() && !dirEntry.getName().contains("..")) {
                    numDirs++;
                }
            }

            routeContext.setLocal("dirUrl", absoluteDirUri);
            routeContext.setLocal("dirPath", absoluteDirUri.substring(getUrlPath().length()));
            routeContext.setLocal("dirEntries", dirEntries);
            routeContext.setLocal("numDirs", numDirs);
            routeContext.setLocal("numFiles", numFiles);
            routeContext.setLocal("diskUsage", diskUsage);
            routeContext.render(directoryTemplate);
        }
    }

    protected String generateDefaultDirectoryListing(RouteContext routeContext, File dir, String absoluteDirUri) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><table>");
        SimpleDateFormat df = new SimpleDateFormat(timestampPattern);
        NumberFormat nf = new DecimalFormat(fileSizePattern);
        for (DirEntry dirEntry : getDirEntries(routeContext, dir, absoluteDirUri)) {
            sb.append(StringUtils.format("<tr><td><a href=\"{}\">{}</a></td><td>{}</td><td>{}</td></tr>\n",
                dirEntry.getUrl(),
                dirEntry.getName(),
                dirEntry.isFile() ? nf.format(dirEntry.getSize()) : "",
                df.format(dirEntry.getLastModified())));
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    public static class DirEntry implements Comparable<DirEntry> {

        private final String url;
        private final File file;

        public DirEntry(String url, File file) {
            this.url = url;
            this.file = file;
        }

        public String getUrl() {
            return url;
        }

        public String getName() {
            return file.getName();
        }

        public long getLength() {
            return file.length();
        }

        public long getSize() {
            return file.length();
        }

        public Date getLastModified() {
            return new Date(file.lastModified());
        }

        public boolean isFile() {
            return file.isFile();
        }

        public boolean isDirectory() {
            return file.isDirectory();
        }

        @Override
        public int compareTo(DirEntry o) {
            return getName().toLowerCase().compareTo(o.getName().toLowerCase());
        }
    }
}
