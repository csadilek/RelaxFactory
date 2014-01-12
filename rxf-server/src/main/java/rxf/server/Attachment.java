package rxf.server;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Attachment {

  @Expose(serialize = false)
  private long length;

  @SerializedName("content_type")
  private String contentType;

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
