package ignatov.nettyserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.router.RouteResult;
import io.netty.handler.codec.http.router.Router;
import io.netty.util.CharsetUtil;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

@ChannelHandler.Sharable
public class HttpRouterServerHandler extends SimpleChannelInboundHandler<HttpRequest> {
    public static final String PUBLIC_DIR = HttpRouterServer.PUBLIC_DIR;
    public static final boolean FILE_MEMORY_CACHING = HttpRouterServer.FILE_MEMORY_CACHING;
    public static final long MEMORY_CACHE_EXPIRES_IN_MS = HttpRouterServer.MEMORY_CACHE_EXPIRES_IN_MS;
    public static final int HTTP_CACHE_SECONDS = 60;
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";

    private final Router<String> router;
    public HashMap<String, cachedStringFile> stringCache = new HashMap<String, cachedStringFile>();
    public HashMap<String, cachedByteArray> byteCache = new HashMap<String, cachedByteArray>();
    public HttpRouterServerHandler(Router<String> router) {
        this.router = router;
    }


    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpRequest req) {


        // 405 if request is not GET
        if (req.getMethod() != HttpMethod.GET) {
            HttpResponse res = HttpMethodIsNotGet();
            flushResponse(ctx, req, res);
        }

        RouteResult<String> routeResult = router.route(req.getMethod(), req.getUri());
        Map<String, String> paramMap = null;
        String paramPathFirst = null;
        if (routeResult.pathParams().isEmpty()) {
            paramMap = null;
        } else {
            paramMap = (Map<String, String>) routeResult.pathParams();
            paramPathFirst = paramMap.get("id");
        }

        // 400 if any query params
        if (!routeResult.queryParams().isEmpty()) {
            HttpResponse res = invalidQueryParams();
            flushResponse(ctx, req, res);
        }

        // URI /public/*
        if (routeResult.target() == "public") {
            String pathToFile = PUBLIC_DIR + paramPathFirst;

            // 304 if have header IF_MODIFIED_SINCE and file was not mod, also checking FileNotFound
            HttpResponse try304 = checkNotModifiedHeaderEtagAndRespond304(req, paramPathFirst);
            if (try304 != null) {
                flushResponse(ctx, req, try304);
            }

            // public/*.jpg *.png
            if (getExtension(paramPathFirst).equals("jpg") || getExtension(paramPathFirst).equals("png")) {
                HttpResponse res = imgResponse(req, router, pathToFile);
                flushResponse(ctx, req, res);
            }

            //public/*.js
            if (getExtension(paramPathFirst).equals("js")) {
                HttpResponse res = jsResponse(req, router, pathToFile);
                flushResponse(ctx, req, res);
            }

            //public/*.css
            if (getExtension(paramPathFirst).equals("css")) {
                HttpResponse res = cssResponse(req, router, pathToFile);
                flushResponse(ctx, req, res);
            }

            // public/*.*
            HttpResponse res = htmlResponse(req, router);
            flushResponse(ctx, req, res);


        } else { // != "public"
//          HttpResponse res = createResponse(req, router);
            HttpResponse res = blankResponse();
            flushResponse(ctx, req, res);

//        if (routeResult.target() == "base64") {
//            HttpResponse res = base64Response(req, router, "public/encodedImage.txt");
//            flushResponse(ctx, req, res);
//        }
//        if (routeResult.target() == "image") {
//            HttpResponse res = imgResponse(req, router, "public/encodedImage.txt");
//            flushResponse(ctx, req, res);
//        }
        }
    }


    private HttpResponse stringFileResponse(HttpRequest req, Router<String> router, String pathString) {
        String content = null;
        content = checkStringContentInCache(req, stringCache);
        if (content == null) {
            content = readStringFile(pathString);
            // 404 File Not Found
            if (content == null) {
                return FileNotFound();
            }
        }

        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(content, CharsetUtil.UTF_8)
        );

        setDateAndCacheHeaders(res, pathString);
        setContentTypeHeader(res, pathString);
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());
        if (FILE_MEMORY_CACHING) stringCache.put(req.getUri(), new cachedStringFile(req.getUri(), content));

        return res;
    }
    private HttpResponse cssResponse(HttpRequest req, Router<String> router, String pathString) {
        return stringFileResponse(req, router, pathString);
    }
    private HttpResponse jsResponse(HttpRequest req, Router<String> router, String pathString) {
        return stringFileResponse(req, router, pathString);
    }
    private HttpResponse htmlResponse(HttpRequest req, Router<String> router) {

        RouteResult<String> routeResult = router.route(req.getMethod(), req.getUri());
        StringBuilder pathToFileSB = new StringBuilder();
        pathToFileSB.append(PUBLIC_DIR);

        if (routeResult.pathParams().isEmpty()) {
            pathToFileSB.append("index.html");
        } else {
            Map<String, String> paramMap = (Map<String, String>) routeResult.pathParams();
            String paramFirst = paramMap.get("id");
            pathToFileSB.append(paramFirst);
        }

        String content = null;
        content = checkStringContentInCache(req, stringCache);
        if (content == null) {
            content = readStringFile(pathToFileSB.toString());
            // 404 File Not Found
            if (content == null) {
                return FileNotFound();
            }
        }

        boolean isCharsetUSASCII = req.headers().contains("Accept-Charset", "US-ASCII", true);

        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(content.toString(), isCharsetUSASCII? CharsetUtil.US_ASCII : CharsetUtil.UTF_8)
        );


        setDateAndCacheHeaders(res, pathToFileSB.toString());
        setContentTypeHeader(res, pathToFileSB.toString());
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        if (FILE_MEMORY_CACHING) stringCache.put(req.getUri(), new cachedStringFile(req.getUri(), content));
        return res;
    }
    private HttpResponse imgResponse(HttpRequest req, Router<String> router, String pathString) {

        RouteResult<String> routeResult = router.route(req.getMethod(), req.getUri());

        byte[] content = null;
        content = checkByteArrayContentInCache(req, byteCache);
        if (content == null) {
            try {
                content = Files.readAllBytes(Paths.get(pathString));
            } catch (NoSuchFileException e) {
                return FileNotFound();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(content)
        );

        setContentTypeHeader(res, pathString);
        setDateAndCacheHeaders(res, pathString);
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());
        if (FILE_MEMORY_CACHING) byteCache.put(req.getUri(), new cachedByteArray(req.getUri(), content));

        return res;
    }

    public HttpResponse checkNotModifiedHeaderEtagAndRespond304(HttpRequest req, String pathToFile) {

        String ifModifiedSince = req.headers().get(HttpHeaders.Names.IF_MODIFIED_SINCE);
        String ifNoneMatch = req.headers().get(HttpHeaders.Names.IF_NONE_MATCH);

        File file = new File("public/" + pathToFile);
        if (file.isHidden() || !file.exists()) {
            return FileNotFound();
        }

        Date fileModifDate = new Date(file.lastModified());

        SimpleDateFormat gmtDateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        gmtDateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
        String ifMatchFileLastModifString = gmtDateFormatter.format(fileModifDate);

        String fileEtag = null;
        try {fileEtag = Base64.getEncoder().encodeToString(ifMatchFileLastModifString.getBytes("utf-8")).toLowerCase();} catch (UnsupportedEncodingException e) {};

        // If-None-Match part
        if (ifNoneMatch != null && !fileEtag.isEmpty()) {
            if (ifNoneMatch.equals(fileEtag)) {
                FullHttpResponse res = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED, Unpooled.buffer(0)
                );
                return res;
            }
        }

        // If-Modified-Since part
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            if (ifMatchFileLastModifString.equals(ifModifiedSince)) {
                FullHttpResponse res = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED, Unpooled.buffer(0)
                );
                return res;
            }
        }
        return null;

    }
    private static HttpResponse HttpMethodIsNotGet() {
        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED,
                Unpooled.copiedBuffer("405 Request method is not GET", CharsetUtil.UTF_8)
        );

        res.headers().set(HttpHeaders.Names.CONTENT_TYPE,   "text/plain");
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        return res;
    }
    private static HttpResponse FileNotFound() {
        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                Unpooled.copiedBuffer("404 File not Found", CharsetUtil.UTF_8)
        );

        res.headers().set(HttpHeaders.Names.CONTENT_TYPE,   "text/plain");
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        return res;
    }
    private static HttpResponse invalidQueryParams() {
        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
                Unpooled.copiedBuffer("400 Bad request", CharsetUtil.UTF_8)
        );

        res.headers().set(HttpHeaders.Names.CONTENT_TYPE,   "text/plain");
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        return res;
    }

    private static HttpResponse blankResponse() {
        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer("<html><body><a href='public/index.html'>index.html</a></body></html>", CharsetUtil.UTF_8)
        );

        res.headers().set(HttpHeaders.Names.CONTENT_TYPE,   "text/html");
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        return res;
    }
    private static HttpResponse createResponse(HttpRequest req, Router<String> router) {
        RouteResult<String> routeResult = router.route(req.getMethod(), req.getUri());

        // Display debug info.
        //
        // For simplicity of this example, route targets are just simple strings.
        // But you can make them classes, and here once you get a target class,
        // you can create an instance of it and dispatch the request to the instance etc.
        StringBuilder content = new StringBuilder();
        content.append("router: \n" + router + "\n");
        content.append("req: " + req + "\n\n");
        content.append("routeResult: \n");
        content.append("target: " + routeResult.target() + "\n");
        content.append("pathParams: " + routeResult.pathParams() + "\n");
        content.append("queryParams: " + routeResult.queryParams() + "\n\n");
        content.append("allowedMethods: " + router.allowedMethods(req.getUri()));

        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(content.toString(), CharsetUtil.UTF_8)
        );

        res.headers().set(HttpHeaders.Names.CONTENT_TYPE,   "text/plain");
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        return res;
    }
    private static HttpResponse base64Response(HttpRequest req, Router<String> router, String pathString) {


        StringBuilder content = new StringBuilder();
        content.append("<!DOCTYPE html><html><head><title></title></head><body style=\"margin:0; padding: 0\"><img src=\"data:image/jpg;base64,");
        try {
            content.append(new String(Files.readAllBytes(Paths.get(pathString))));

        } catch (IOException e) {
            e.printStackTrace();
        }
        content.append("\"></body></html>");


        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(content.toString(), CharsetUtil.UTF_8)
        );

        res.headers().set(HttpHeaders.Names.CONTENT_TYPE,   "text/html");
        res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, res.content().readableBytes());

        return res;

    }


    private static ChannelFuture flushResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        if (!HttpHeaders.isKeepAlive(req)) {
            return ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        } else {
            res.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
            return ctx.writeAndFlush(res);
        }
    }





    //hash stuff
    private static void setDateAndCacheHeaders(HttpResponse response, String pathToFile) {

        File fileToCache = new File(pathToFile);

        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        Calendar time = new GregorianCalendar();
        response.headers().set("Date", dateFormatter.format(time.getTime()));

        // Add cache headers
        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaders.Names.EXPIRES, dateFormatter.format(time.getTime()));
        response.headers().set(HttpHeaders.Names.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);

        response.headers().set(HttpHeaders.Names.LAST_MODIFIED, dateFormatter.format(new Date(fileToCache.lastModified())));

        //SimpleDateFormat fileDateFormatter = new SimpleDateFormat(CUSTOM_DATE_FORMAT, Locale.US);
        String lastModifiedString = dateFormatter.format(new Date(fileToCache.lastModified()));
        try {response.headers().set(HttpHeaders.Names.ETAG, Base64.getEncoder().encodeToString(lastModifiedString.getBytes("utf-8")).toLowerCase());} catch (UnsupportedEncodingException e) {}
    }
    private static void setContentTypeHeader(HttpResponse response, String pathString) {
        File file = new File(pathString);
        MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();
        response.headers().set(HttpHeaders.Names.CONTENT_TYPE, mimeTypesMap.getContentType(file.getPath()));
    }
    public String getExtension(String s) {
        if (s.contains(".")) {
            return s.split("\\.")[1];
        } else return "";

    }
    class cachedByteArray {
        String uri;
        byte[] byteArray;
        long gotInCache;

        public cachedByteArray(String uri, byte[] byteArray) {
            this.uri = uri;
            this.byteArray = byteArray;
            gotInCache = new Date().getTime();
        }
    }
    class cachedStringFile {
        String uri;

        public cachedStringFile(String uri, String stringFile) {
            this.uri = uri;
            this.stringFile = stringFile;
            gotInCache = new Date().getTime();
        }

        String stringFile;
        long gotInCache;

    }
    private String checkStringContentInCache(HttpRequest req, Map<String, cachedStringFile> stringCache) {
        if (stringCache.containsKey(req.getUri()) && FILE_MEMORY_CACHING) {
            if (stringCache.get(req.getUri()).gotInCache > new Date().getTime() - MEMORY_CACHE_EXPIRES_IN_MS) {
                String content = stringCache.get(req.getUri()).stringFile;
                return content;
            } else { // cache got expired
                stringCache.remove(req.getUri());
                return null;
            }
        }
        return null;
    }
    private byte[] checkByteArrayContentInCache(HttpRequest req, Map<String, cachedByteArray> byteCache) {
        if (byteCache.containsKey(req.getUri()) && FILE_MEMORY_CACHING) {
            if (byteCache.get(req.getUri()).gotInCache > new Date().getTime() - MEMORY_CACHE_EXPIRES_IN_MS) {
                byte[] content = byteCache.get(req.getUri()).byteArray;
                return content;
            } else { // cache got expired
                byteCache.remove(req.getUri());
                return null;
            }
        }
        return null;
    }
    private String readStringFile(String pathString) {
        String content = null;
        try {
            content = new String(Files.readAllBytes(Paths.get(pathString)));
            return content;
        } catch (NoSuchFileException e) {

        } catch (IOException e) {
            e.printStackTrace();
        }
        return content;
    }
}