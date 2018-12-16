package org.batfish.question.routes;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.batfish.datamodel.Ip;

/**
 * Class representing the secondary key used for grouping {@link
 * org.batfish.datamodel.AbstractRoute}s and {@link org.batfish.datamodel.BgpRoute}s
 */
public class RouteRowSecondaryKey {

  @Nonnull private final Ip _nextHopIp;

  @Nullable private final String _protocol;

  public RouteRowSecondaryKey(@Nonnull Ip nextHopIp, @Nullable String protocol) {
    _nextHopIp = nextHopIp;
    _protocol = protocol;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RouteRowSecondaryKey that = (RouteRowSecondaryKey) o;
    return Objects.equals(_nextHopIp, that._nextHopIp) && Objects.equals(_protocol, that._protocol);
  }

  @Override
  public int hashCode() {
    return Objects.hash(_nextHopIp, _protocol);
  }

  @Nonnull
  public Ip getNextHopIp() {
    return _nextHopIp;
  }

  @Nullable
  public String getProtocol() {
    return _protocol;
  }
}
