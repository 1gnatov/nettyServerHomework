# nettyServerHomework
Learning netty 4 and trying to make some simple static file server with client caching and server memory caching  
Based on netty 4 and netty-router  

Catalog for static files - in HttpRouterServer PUBLIC_DIR constant  
Memory cache - enable in HttpRouterServer FILE_MEMORY_CACHING constant, file caching for MEMORY_CACHE_EXPIRES_IN_MS, then it removes from cache on request to this file and replacing for new one.  
Not-GET req -> 405   
If file not found -> 404  
If any request parameters -> 400  
Correct working If If-Modified-Since and If-None-Match -> 304, Etag is just Base64 encoded lastModified string;  
Correct working Accept-Charset for html files - respond html file in UTF-8 or US-ASCII on request.  
Correct working Content-type  
Content can be text/html, application/javascript, image/jpeg, image/png, text/css  
  
Dependencies: in pom.xml, maven dependencies  
