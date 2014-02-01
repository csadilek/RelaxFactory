package rxf.server;

public class Attachment {

//  @Expose(serialize = false)
  private long length;

//  @SerializedName("content_type")
  private String contentType;

  private boolean stub;

  public long getLength() {
    return length;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

}
