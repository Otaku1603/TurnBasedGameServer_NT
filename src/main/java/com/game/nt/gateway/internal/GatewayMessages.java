package com.game.nt.gateway.internal;

import com.game.nt.gateway.ProtocolVersion;
import com.game.nt.proto.GameProto.ErrorResponse;
import com.game.nt.proto.GameProto.GameMessage;
import com.game.nt.proto.GameProto.MessageType;
import com.game.nt.shared.error.ErrorCode;

/** 出站消息构造工具。 */
public final class GatewayMessages {

    private GatewayMessages() {
    }

    /** 构造统一错误响应（回填 requestId，便于客户端定位失败请求）。 */
    public static GameMessage error(long requestId, ErrorCode code, String message) {
        ErrorResponse.Builder err = ErrorResponse.newBuilder()
                .setCode(com.game.nt.proto.GameProto.ErrorCode.forNumber(code.protoValue()))
                .setMessage(message == null ? code.defaultMessage() : message)
                .setRequestId(requestId);
        return GameMessage.newBuilder()
                .setVersion(ProtocolVersion.CURRENT)
                .setType(MessageType.ERROR)
                .setRequestId(requestId)
                .setErrorResponse(err)
                .build();
    }
}
