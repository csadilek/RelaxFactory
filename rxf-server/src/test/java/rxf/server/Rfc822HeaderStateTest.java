package rxf.server;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static one.xio.HttpMethod.UTF8;

public class Rfc822HeaderStateTest {
  @Test
  public void testAppendHeadersOne() {
    Rfc822HeaderState state = new Rfc822HeaderState("One");
    assertEquals(1, state.headerInterest().length);
    state.addHeaderInterest("Two");
    assertEquals(2, state.headerInterest().length);
    String[] expecteds = {"One", "Two"};
    Assert.assertArrayEquals(expecteds, state.headerInterest());
  }

  @Test
  public void testAppendHeadersMany() {
    Rfc822HeaderState state = new Rfc822HeaderState("One");
    assertEquals(1, state.headerInterest().length);
    state.addHeaderInterest("Two", "Three");
    assertEquals(3, state.headerInterest().length);
    String[] expecteds = {"One", "Three", "Two",};
    Assert.assertArrayEquals(expecteds, state.headerInterest());
  }

  @Test
  public void testAsRequestHeaderByteBuffer() {
    Rfc822HeaderState req = new Rfc822HeaderState();
    req.methodProtocol("VERB").pathResCode("/noun").protocolStatus("HTTP/1.0").headerString(
        "Header", "value").headerString("Header2", "value2");
    ByteBuffer buf = req.asRequestHeaderByteBuffer();
    String result = UTF8.decode(buf.duplicate()).toString();

    assertEquals("VERB /noun HTTP/1.0\r\nHeader: value\r\nHeader2: value2\r\n\r\n", result);
  }

  @Test
  public void testApplySimpleResponse() {
    ByteBuffer simpleResponse =
        ByteBuffer.wrap("HTTP/1.0 200 OK\r\nServer: NotReallyAServer\r\n\r\n".getBytes(UTF8));

    Rfc822HeaderState state = new Rfc822HeaderState();
    state.addHeaderInterest("Server");
    state.apply(simpleResponse);

    final String actual = state.methodProtocol();
    assertEquals("HTTP/1.0", actual);
    final String actual1 = state.pathResCode();
    assertEquals("200", actual1);
    final String actual2 = state.protocolStatus();
    assertEquals("OK", actual2);
    final String server = state.headerString("Server");
    assertEquals("NotReallyAServer", server);
  }

  @Test
  public void testApplySimpleRequest() {
    ByteBuffer simpleRequest =
        ByteBuffer
            .wrap("GET /file/from/path.suffix HTTP/1.0\r\nContent-Type: application/json\r\n\r\n"
                .getBytes(UTF8));

    Rfc822HeaderState state = new Rfc822HeaderState("Content-Type");
    state.apply(simpleRequest);

    assertEquals("GET", state.methodProtocol());
    assertEquals("/file/from/path.suffix", state.pathResCode());
    assertEquals("HTTP/1.0", state.protocolStatus());
    assertEquals("application/json", state.headerString("Content-Type"));
  }

  @Test
  public void testAsResponseHeaderByteBuffer() {
    Rfc822HeaderState resp = new Rfc822HeaderState();
    resp.methodProtocol("HTTP/1.0").pathResCode("501").protocolStatus("Unsupported Method")
        .headerString("Connection", "close");
    ByteBuffer buf = resp.asResponseHeaderByteBuffer();
    String result = UTF8.decode(buf).toString();
    assertEquals("HTTP/1.0 501 Unsupported Method\r\nConnection: close\r\n\r\n", result);
  }

  @Test
  public void testFilteredCookies() {
    ByteBuffer buf = (ByteBuffer) UTF8.encode(CookieRfc6265UtilTest.H4).rewind();

    Rfc822HeaderState.HttpRequest httpRequest =
        ActionBuilder.get().state().$req().cookieInterest("SAPISID", "SSID");
    httpRequest.apply(buf);

    Map<String, String> cookies = httpRequest.getCookies("SAPISID", "SSID");
    assertEquals(String.valueOf(cookies),
        "{SAPISID=tgSIdsbz9xkHOX1P/Agnhtasdf2FpF, SSID=A3ZS9cJVATN-UjcZP}");

    String ssid = httpRequest.getCookie("SSID");
    assertEquals("A3ZS9cJVATN-UjcZP", ssid);

  }

  @Test
  public void testStateTransition() {
    Rfc822HeaderState state = ActionBuilder.get().state();/*
                                                          System.err.println(
                                                          state.as(String.class));*/
    Rfc822HeaderState.HttpRequest httpRequest = state.$req();
    httpRequest.headerStrings().put("foo", "bar");

    Rfc822HeaderState.HttpResponse httpResponse = httpRequest.$res();
    /*System.err.println(httpResponse.as(String.class));
     */

    assertTrue(httpResponse.headerStrings().containsValue("bar"));
    assertTrue(httpResponse.headerStrings().containsKey("foo"));

  }

}
