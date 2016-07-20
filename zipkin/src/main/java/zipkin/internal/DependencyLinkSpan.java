/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.internal;

import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Constants;
import zipkin.Span;

import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.equal;

/**
 * Internal type used by {@link DependencyLinker linker} that holds the minimum state needed to
 * aggregate {@link zipkin.DependencyLink dependency links}.
 */
// fields not exposed as public to further discourage use as a general type
public final class DependencyLinkSpan {
  /**
   * Indicates the primary span type.
   */
  enum Kind {
    CLIENT,
    /** The span includes a {@link zipkin.Constants#SERVER_RECV}. */
    SERVER,
    UNKNOWN
  }

  final long traceId;
  @Nullable
  final Long parentId;
  final long id;
  final Kind kind;
  @Nullable
  final String service;
  @Nullable
  final String peerService;

  DependencyLinkSpan(long traceId, Long parentId, long id, Kind kind, String service,
      String peerService) {
    this.traceId = traceId;
    this.parentId = parentId;
    this.id = id;
    this.kind = checkNotNull(kind, "kind");
    this.service = service;
    this.peerService = peerService;
  }

  @Override public String toString() {
    StringBuilder json = new StringBuilder();
    json.append("{\"traceId\": \"").append(Util.toLowerHex(traceId)).append('\"');
    if (parentId != null) {
      json.append(", \"parentId\": \"").append(Util.toLowerHex(parentId)).append('\"');
    }
    json.append(", \"id\": \"").append(Util.toLowerHex(id)).append('\"');
    json.append(", \"kind\": \"").append(kind).append('\"');
    if (service != null) json.append(", \"service\": \"").append(service).append('\"');
    if (peerService != null) json.append(", \"peerService\": \"").append(peerService).append('\"');
    return json.append("}").toString();
  }

  /** Only considers ID fields, as these spans are not expected to repeat */
  @Override
  public boolean equals(Object o) {
    if (o == this) return true;
    if (o instanceof Span) {
      Span that = (Span) o;
      return (this.traceId == that.traceId)
          && equal(this.parentId, that.parentId)
          && (this.id == that.id);
    }
    return false;
  }

  /** Only considers ID fields, as these spans are not expected to repeat */
  @Override
  public int hashCode() {
    int h = 1;
    h *= 1000003;
    h ^= (traceId >>> 32) ^ traceId;
    h *= 1000003;
    h ^= (parentId == null) ? 0 : parentId.hashCode();
    h *= 1000003;
    h ^= (id >>> 32) ^ id;
    return h;
  }

  public static Builder builder(long traceId, Long parentId, long spanId) {
    return new Builder(traceId, parentId, spanId);
  }

  public static DependencyLinkSpan from(Span s) {
    DependencyLinkSpan.Builder linkSpan = DependencyLinkSpan.builder(s.traceId, s.parentId, s.id);
    for (BinaryAnnotation a : s.binaryAnnotations) {
      if (a.key.equals(Constants.CLIENT_ADDR) && a.endpoint != null) {
        linkSpan.caService(a.endpoint.serviceName);
      } else if (a.key.equals(Constants.SERVER_ADDR) && a.endpoint != null) {
        linkSpan.saService(a.endpoint.serviceName);
      }
    }
    for (Annotation a : s.annotations) {
      if (a.value.equals(Constants.SERVER_RECV) && a.endpoint != null) {
        linkSpan.srService(a.endpoint.serviceName);
        break;
      }
    }
    return linkSpan.build();
  }

  public static final class Builder {
    private final long traceId;
    private final Long parentId;
    private final long spanId;
    private String srService;
    private String caService;
    private String saService;

    Builder(long traceId, Long parentId, long spanId) {
      this.traceId = traceId;
      this.spanId = spanId;
      this.parentId = parentId;
    }

    /**
     * {@link zipkin.Constants#SERVER_RECV} is the preferred name of server, and this is a
     * traditional span.
     */
    public Builder srService(String srService) {
      this.srService = srService;
      return this;
    }

    /**
     * {@link zipkin.Constants#CLIENT_ADDR} is read to see calls into the root span from
     * uninstrumented clients.
     */
    public Builder caService(String caService) {
      this.caService = caService;
      return this;
    }

    /**
     * {@link zipkin.Constants#SERVER_ADDR} is only read at the leaf, when a client calls an
     * un-instrumented server.
     */
    public Builder saService(String saService) {
      this.saService = saService;
      return this;
    }

    public DependencyLinkSpan build() {
      // Finagle labels two sides of the same socket ("ca", "sa") with the same name.
      // Skip the client side, so it isn't mistaken for a loopback request
      if (equal(saService, caService)) {
        caService = null;
      }
      if (srService != null) {
        return new DependencyLinkSpan(traceId, parentId, spanId, Kind.SERVER, srService, caService);
      } else if (saService != null) {
        return new DependencyLinkSpan(traceId, parentId, spanId, Kind.CLIENT, caService, saService);
      }
      return new DependencyLinkSpan(traceId, parentId, spanId, Kind.UNKNOWN, null, null);
    }
  }
}
