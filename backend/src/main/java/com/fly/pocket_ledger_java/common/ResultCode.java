package com.fly.pocket_ledger_java.common;

/**
 * 统一业务状态码，与《Pocket Ledger 接口文档》（docs/pocket-ledger-api.html）"通用错误"一节一一对应。
 * 契约约定 HTTP 状态码与响应体里的 code 恒等，所以这里同时就是 HTTP 状态码。
 * 码值与文案禁止散落在 Controller/Service 里，统一从这里取。
 */
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),

    /** 框架级 400 兜底 */
    BAD_REQUEST(400, "请求参数不合法"),

    /** 认证失败：未登录 / token 无效、过期、已拉黑 / 用户已不存在 */
    UNAUTHORIZED(401, "未登录或登录已失效"),

    /** 账号密码错误（防用户名枚举：用户不存在与密码错误同文案） */
    BAD_CREDENTIALS(401, "用户名或密码错误"),

    /** 框架级 404：路径不存在 */
    NOT_FOUND(404, "Not Found"),

    /** 框架级 405：请求方法不允许 */
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),

    /** 资源冲突：用户名已被占用 */
    USERNAME_OCCUPIED(409, "用户名已被占用"),

    /** 参数校验失败（FastAPI/Pydantic 语义），具体原因由各校验点给出 */
    PARAM_INVALID(422, "参数错误"),

    CATEGORY_NOT_FOUND(400, "分类不存在"),
    BREAKDOWN_EXCEEDS_TOTAL(400, "细分金额不能超过账单总额"),
    BILL_NOT_FOUND(404, "账单不存在"),
    WRITE_CONFLICT(409, "数据正在被修改，请稍后重试"),

    /** 文件格式、数量或内容不符合头像规则。 */
    AVATAR_INVALID(422, "头像仅支持 JPG、PNG 格式"),
    /** 最大 2 MiB，HTTP 状态与响应 code 一致。 */
    AVATAR_TOO_LARGE(413, "头像不能超过 2 MB"),
    /** 必须成功的 OSS 上传暂不可用；删除旧图失败不使用此响应。 */
    AVATAR_STORAGE_UNAVAILABLE(503, "头像存储暂不可用，请稍后重试"),

    /** 未处理的服务端异常 */
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
