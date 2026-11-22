package app.androidwebcam.core.cloud

/** The instructions a cloud agent gets: one command that fetches a picture and decrypts it. */
object CloudInstructions {
    /**
     * Node.js script (`node -e '<script>' -- <key> <agent id> <encrypted file> <picture file>`; the
     * `--` matters: keys and ids may start with "-"):
     * decrypts and checks the picture and rejects anything older than five minutes.
     */
    const val DECRYPT_JS = "const c=require(\"crypto\"),f=require(\"fs\");const [k,id,src,dst]=process.argv.slice(1);" +
        "const d=f.readFileSync(src);if(d.length<36||d.toString(\"latin1\",0,4)!==\"AWC1\")throw new Error(\"not an Android WebCam picture\");" +
        "const x=c.createDecipheriv(\"aes-256-gcm\",Buffer.from(k,\"base64url\"),d.subarray(4,16));x.setAAD(Buffer.from(\"AWC1\"+id));" +
        "x.setAuthTag(d.subarray(d.length-16));const p=Buffer.concat([x.update(d.subarray(16,d.length-16)),x.final()]);" +
        "const age=Date.now()-Number(p.readBigUInt64BE(0));if(age>300000||age<-300000)throw new Error(\"picture is not current\");" +
        "f.writeFileSync(dst,p.subarray(8));console.log(dst+\" (taken \"+Math.max(0,Math.round(age/1000))+\" s ago)\")"

    fun normalizeRelay(relay: String): String = relay.trim().trimEnd('/')

    fun command(relay: String, credential: CloudCredential): String =
        "curl -fsS -H \"Authorization: Bearer ${credential.token}\" -o /tmp/awc.enc ${normalizeRelay(relay)}/v1/snapshot && " +
            "node -e '$DECRYPT_JS' -- ${credential.e2eKeyText} ${credential.agentId} /tmp/awc.enc /tmp/awc-camera.jpg"

    fun text(relay: String, credential: CloudCredential, chinese: Boolean): String {
        val command = command(relay, credential)
        return if (chinese) {
            """
            |# 手机摄像头（Android WebCam，云端访问）
            |
            |当我问到摄像头或它拍到了什么时，运行下面的命令拍一张新画面，然后查看生成的图片文件 /tmp/awc-camera.jpg：
            |
            |$command
            |
            |- 需要 curl 和 Node.js。画面在手机上就已加密，只有这条命令里的密钥能解开，中转站看不到画面。
            |- 每次运行都会拍一张新画面（手机后置摄像头，没有镜像）；每 5 秒最多 1 张。
            |- 图片只放在 /tmp，不要复制进项目或提交到仓库；命令里的令牌和密钥也不要写进代码、日志或提交。
            |- 失败时：HTTP 503 = 手机没在推流、没开云端访问或不在线；HTTP 401 = 这个 agent 已在手机上被撤销；HTTP 429 = 太快了，几秒后再试；“picture is not current” = 收到的不是刚拍的画面，请重试。
            """.trimMargin()
        } else {
            """
            |# Phone camera (Android WebCam, cloud access)
            |
            |When I ask about the camera or what it sees, take a new picture with this command and then look at the image file /tmp/awc-camera.jpg:
            |
            |$command
            |
            |- Needs curl and Node.js. The picture is encrypted on the phone; only the key in this command decrypts it, the relay cannot see it.
            |- Each run takes a new picture (the phone's rear camera, not mirrored); at most one picture every 5 seconds.
            |- Keep the picture in /tmp: never copy it into a project or commit it; never put the token or key from this command into code, logs or commits.
            |- If it fails: HTTP 503 = the phone is not streaming, cloud access is off or the phone is offline; HTTP 401 = this agent was revoked on the phone; HTTP 429 = too fast, retry in a few seconds; "picture is not current" = not a fresh picture, retry.
            """.trimMargin()
        }
    }
}
