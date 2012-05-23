package rxf.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.web.bindery.requestfactory.shared.Locator;
import one.xio.AsioVisitor;
import one.xio.HttpHeaders;
import one.xio.HttpMethod;

import static java.lang.Math.max;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static one.xio.HttpMethod.UTF8;
import static rxf.server.BlobAntiPatternObject.EXECUTOR_SERVICE;
import static rxf.server.BlobAntiPatternObject.GSON;
import static rxf.server.BlobAntiPatternObject.arrToString;
import static rxf.server.BlobAntiPatternObject.createCouchConnection;
import static rxf.server.BlobAntiPatternObject.getPathIdVer;
import static rxf.server.BlobAntiPatternObject.getReceiveBufferSize;
import static rxf.server.BlobAntiPatternObject.getSendBufferSize;
import static rxf.server.BlobAntiPatternObject.recycleChannel;

/**
 * User: jim
 * Date: 5/10/12
 * Time: 7:37 AM
 */
public abstract class CouchLocator<T> extends Locator<T, String> {

  public static final String CONTENT_LENGTH = "Content-Length";
  private String orgname = "rxf_";//default

  public String getPathPrefix() {
    return getOrgname() + getDomainType().getSimpleName().toLowerCase();
  }

  /**
   * <pre>
   * POST /rosession HTTP/1.1
   * Content-Type: application/json
   * Content-Length: 133
   *
   * [data not shown]
   * HTTP/1.1 201 Created
   *
   * [data not shown]
   * </pre>
   *
   * @param clazz
   * @return
   */
  @Override
  public T create(Class<? extends T> clazz) {
    try {
      return clazz.newInstance();
    } catch (InstantiationException e) {
      e.printStackTrace();
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    throw new UnsupportedOperationException("no default ctor " + HttpMethod.wheresWaldo(3));
  }

  @Override
  public T find(Class<? extends T> clazz, final String id) {

    String s = null;
    try {
      final SocketChannel channel = createCouchConnection();
      final AtomicReference<String> take = new AtomicReference<String>();

      Callable callable = new Callable() {
        public Object call() throws Exception {
          Exchanger<String> retVal = new Exchanger<String>();
          HttpMethod.enqueue(channel, OP_CONNECT | OP_WRITE, BlobAntiPatternObject.fetchJsonByPath(channel, retVal, getPathPrefix(), id));
          take.set(retVal.exchange(null, 3, TimeUnit.SECONDS));

          return null;
        }
      };
      take.set((String) EXECUTOR_SERVICE.submit(callable).get());
      s = take.get();
    } catch (Exception e) {
      e.printStackTrace();
    }

    return GSON.fromJson(s, getDomainType());
  }

  /**
   * used by CouchAgent to create event channels on entities by sending it a locator
   *
   * @return
   */
  @Override
  abstract public Class<T> getDomainType();

  @Override
  abstract public String getId(T domainObject);

  @Override
  public Class<String> getIdType() {
    return String.class;
  }

  @Override
  abstract public Object getVersion(T domainObject);

  public String getOrgname() {
    return orgname;
  }

  public CouchTx persist(final T domainObject) throws Exception {
    return (CouchTx) EXECUTOR_SERVICE.submit(new Callable<Object>() {
      public Object call() throws Exception {
        HttpMethod method = HttpMethod.POST;
        String[] pathIdPrefix = {getPathPrefix()};
        String deliver, payload = deliver = GSON.toJson(domainObject);
        final ByteBuffer encode1 = UTF8.encode(deliver);


        Map cheat = null;
        cheat = GSON.fromJson(payload, Map.class);
        boolean hasRev = cheat.containsKey("_rev");
        boolean hasId = cheat.containsKey("_id");
        Object id = cheat.get("_id");
        if (hasId)
          if (!hasRev) {
            pathIdPrefix = new String[]{getPathPrefix(), (String) id};
            method = HttpMethod.HEAD;
          } else {
            Object rev = cheat.get("_rev");
            pathIdPrefix = new String[]{getPathPrefix(), (String) id, (String) rev,};
            method = HttpMethod.PUT;
          }

        switch (method) {
          case HEAD: {
            final Exchanger<ByteBuffer> inner = new Exchanger<ByteBuffer>();
            String m = new StringBuilder().append(method.name()).append(" ").append(getPathIdVer(pathIdPrefix)).append(" HTTP/1.1\r\n\r\n").toString();
            final String finalM1 = m;

            HttpMethod.enqueue(createCouchConnection(), OP_CONNECT | OP_WRITE, new AsioVisitor.Impl() {


              @Override
              public void onRead(SelectionKey key) throws Exception {
//                        fetch from  ETag: "3-9a5fe45b4e065e3604f1f746816c1926"
                ByteBuffer headerBuf = ByteBuffer.allocateDirect(getReceiveBufferSize());
                final SocketChannel channel = (SocketChannel) key.channel();
                int read = channel.read(headerBuf);

                headerBuf.flip();
                while (headerBuf.hasRemaining() && '\n' == headerBuf.get()) ;//pv

                int mark = headerBuf.position();
                ByteBuffer methodBuf = (ByteBuffer) headerBuf.duplicate().flip().position(mark);
                String[] resCode = UTF8.decode(methodBuf).toString().trim().split(" ");
                if (resCode[1].startsWith("20")) {
                  int[] headers = HttpHeaders.getHeaders((ByteBuffer) headerBuf.clear()).get("ETag");

                  inner.exchange((ByteBuffer) headerBuf.position(headers[0]).limit(headers[1]));
                }
              }

              @Override
              public void onWrite(SelectionKey key) throws Exception {
                final SocketChannel channel = (SocketChannel) key.channel();
                channel.write(UTF8.encode(finalM1));
                key.interestOps(OP_READ);
              }
            });

            ByteBuffer take = inner.exchange(null, 250, TimeUnit.MILLISECONDS);
            String newVer = UTF8.decode(take).toString();
            System.err.println("HEAD appends " + arrToString(pathIdPrefix) + " with " + newVer);
            pathIdPrefix = new String[]{getPathPrefix(), (String) id, newVer};
          }
          case PUT:
          case POST: {
            try {
              StringBuilder m = new StringBuilder().append(method.name()).append(" ").append(getPathIdVer(pathIdPrefix)).append(" HTTP/1.1\r\n").append("Content-Length: ").append(encode1.limit()).append("\r\nAccept: */*\r\nContent-Type: application/json\r\n\r\n");
              final String str = m.toString();
              final ByteBuffer encode = UTF8.encode(str);


              final ByteBuffer cursor = (ByteBuffer) ByteBuffer.allocateDirect(max(getSendBufferSize(), encode.limit() + encode1.limit())).put(encode).put(encode1).flip();
              final Exchanger<ByteBuffer> exchanger = new Exchanger<ByteBuffer>();

              HttpMethod.enqueue(createCouchConnection(), OP_WRITE | OP_CONNECT, new AsioVisitor.Impl() {
                @Override
                public void onWrite(SelectionKey key) throws Exception {
                  final SocketChannel channel = (SocketChannel) key.channel();
                  channel.write(cursor);
                  if (!cursor.hasRemaining()) {
                    key.interestOps(OP_READ).attach(new Impl() {
                      @Override
                      public void onRead(SelectionKey key) throws Exception {

                        final ByteBuffer dst = ByteBuffer.allocateDirect(getReceiveBufferSize());
                        channel.read(dst);
                        final Rfc822HeaderState rfc822HeaderState = new Rfc822HeaderState(CONTENT_LENGTH).apply((ByteBuffer) dst.flip());
                        final String pathRescode = rfc822HeaderState.getPathRescode();
                        if (pathRescode.startsWith("20")) {
                          final int len = Integer.parseInt((String) rfc822HeaderState.getHeaderStrings().get(CONTENT_LENGTH));
                          final ByteBuffer cursor = ByteBuffer.allocate(len).put(dst);
                          if (!cursor.hasRemaining()) {
                            exchanger.exchange(cursor);
                            recycleChannel(channel);
                          } else {
                            key.attach(new Impl() {
                              @Override
                              public void onRead(SelectionKey key) throws Exception {
                                channel.read(cursor);
                                if (!cursor.hasRemaining()) {
                                  exchanger.exchange(cursor);
                                  recycleChannel(channel);
                                }
                              }
                            });
                          }
                        } else {
                          exchanger.exchange(null, 3, TimeUnit.SECONDS);
                        }
                      }
                    });
                  }
                }
              });
              final ByteBuffer exchange = (ByteBuffer) exchanger.exchange(null, 3, TimeUnit.SECONDS).flip();
              final String json = UTF8.decode(exchange).toString();

              return GSON.fromJson(json, CouchTx.class);

            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
          default:
            return null;
        }


      }
    }).get();
  }

  List<T> findAll() {
    return null;  //To change body of created methods use File | Settings | File Templates.
  }

  List<T> search
      (String
           queryParm) {
    return null;  //To change body of created methods use File | Settings | File Templates.
  }

  /**
   * tbd -- longpolling feed rf token
   *
   * @param queryParm
   * @return
   */

  String searchAsync(String queryParm) {
    return null;  //To change body of created methods use File | Settings | File Templates.
  }
}