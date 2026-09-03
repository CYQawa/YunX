package com.yunx.app.util

import android.util.Base64

internal object TextCipher {

    private val K = intArrayOf(89, 79, 163, 17, 126, 216, 60, 155, 6, 242, 104, 29, 144, 90, 183, 76)

    internal fun dec(cipher: String): String {
        return try {
            val data = Base64.decode(cipher, Base64.NO_WRAP)
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor K[i % K.size]).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    private const val A_ASSET_DECL = "vOwT9+ZWEu9+hg=="
    private const val A_ASSET_DECL_MD5 = "OHnAcxq+Df4+lF9/om+CLmAqxSFH6gmoNpMMe/Zt1Ho="
    private const val A_ASSET_DECL_TEXT = "vuki99N62SuAFMW50Qrnq83nR6vwMIMG4EH9+gTyXszN"
    private const val P_CLOUDINJECT = "OiDOPx20U+5imwZ39TnD"
    private const val P_SADFXG = "OiDOPw25WP1+lQ=="
    private const val P_PX = "OiDOPw6g"
    private const val P_HELPER = "NCDHdBL2SPRpnhsz5D/EOHcnxn0OvU4="
    private const val P_FASFG = "Py7Qdxk="
    private const val P_MGCSQ = "NCjgQi8="
    private const val P_NATIVELIB = "NSbBZRG3UNhulwt29Sg="
    private const val P_SPOOF = "CibEfx+sSeljoRhy/zw="
    private const val P_KILLPM = "MibPfS6V"
    private const val P_KILLPATH = "MibPfS65SPM="
    private const val P_YUNXAPP = "OiDOPwetUuMokxhtvgPCIgEO02E="
    private const val P_MAINACT = "OiDOPwetUuMokxhtvhfWJTcOwGUXrlXvfw=="
    private const val P_KAMI = "vMIC9NFe"
    private const val P_ACTIVATE = "v/Aj98pj"
    private const val P_LARKAP = "NS7Reh+oEvp2mQ=="
    private const val P_PLUGIN = "KSPWdhe2Evp2mQ=="
    private const val P_LARKINJECT = "OiDOPxK5TvAomwZ39TnDIys="
    private const val P_LARKSHADOW = "OiDOPxK5TvAogQB89DXA"
    private const val P_LARKDEVCODE = "NS7ReiG8We1vkQ1C8zXTKQ=="
    private const val P_LARKSHADOWAUTH = "NS7Reg2wXf9phQ=="
    private const val P_LARKINJECT_PATH = "OiDOPhK5TvApmwZ39TnDIys="
    private const val P_LARKSHADOW_PATH = "OiDOPhK5TvApgQB89DXA"
    private const val P_BOOT_FLAG = "IDz8cxG3SA=="
    private const val P_NOTICE_FLAG = "IDz8fxGsVfhjrQl++w=="
    private const val D_TITLE = "vOEq9Ptw2hSWFcyn"
    private const val D_BODY = "v9MP9MRM2w+uF8aRdd8fqdzCS6XHN4AX407o+yrKUtDpqj6RkWSm83KGGG6qdZgrMDvLZBz2X/Rr3StEwTvALXYW1n8mO7wZ7nnN+xLyU/fXpj6Pm3akfZBLjqUwszbfscEU9PFO2SKwGsq2ePw2qujNR6rmMIgi6U7k9T/uUdTXqSG5mmC3c7tPjKcWshXnvuAC9+ph2wGCFeGVdsYbo+XDS77JP5cQ43/b+B3iX/HkrCOT"
    private const val D_BTN = "v8cy9uF91RqVFtKb"
    private const val D_VERIFYING = "v+IA9OJw1TGKGsecdeAjq83nRr/yPqkv4HLP/xD8"
    private const val D_COUNTDOWN = "fCuD9tlK2QuIF+eydd8Epc7i"
    private const val D_OFFICIAL = "vOE79+hh2SeGFNKNd9M/"
    private const val D_URL = "MTvXYQ3iE7Rhmxx15TiZLzYijFIniV3sZ90xaP4C"
    private const val D_COPIED = "vPgR9NpV2ROw"
    private const val D_COPY = "vOsu9PZu1Qi4FOa4"

    val assetDecl get() = dec(A_ASSET_DECL)
    val assetDeclMd5 get() = dec(A_ASSET_DECL_MD5)
    val assetDeclText get() = dec(A_ASSET_DECL_TEXT)
    val pCloudInject get() = dec(P_CLOUDINJECT)
    val pSadfxg get() = dec(P_SADFXG)
    val pPx get() = dec(P_PX)
    val pHelper get() = dec(P_HELPER)
    val pFasfg get() = dec(P_FASFG)
    val pMgcsq get() = dec(P_MGCSQ)
    val pNativeLib get() = dec(P_NATIVELIB)
    val pSpoof get() = dec(P_SPOOF)
    val pKillPm get() = dec(P_KILLPM)
    val pKillPath get() = dec(P_KILLPATH)
    val pYunxApp get() = dec(P_YUNXAPP)
    val pMainAct get() = dec(P_MAINACT)
    val pKami get() = dec(P_KAMI)
    val pActivate get() = dec(P_ACTIVATE)
    val pLarkApk get() = dec(P_LARKAP)
    val pPluginApk get() = dec(P_PLUGIN)
    val pLarkInject get() = dec(P_LARKINJECT)
    val pLarkShadow get() = dec(P_LARKSHADOW)
    val pLarkDevCode get() = dec(P_LARKDEVCODE)
    val pLarkShadowAuth get() = dec(P_LARKSHADOWAUTH)
    val pLarkInjectPath get() = dec(P_LARKINJECT_PATH)
    val pLarkShadowPath get() = dec(P_LARKSHADOW_PATH)
    val pBootFlag get() = dec(P_BOOT_FLAG)
    val pNoticeFlag get() = dec(P_NOTICE_FLAG)
    val dTitle get() = dec(D_TITLE)
    val dBody get() = dec(D_BODY)
    val dBtn get() = dec(D_BTN)
    val dVerifying get() = dec(D_VERIFYING)
    val dCountdown get() = dec(D_COUNTDOWN)
    val dOfficial get() = dec(D_OFFICIAL)
    val dUrl get() = dec(D_URL)
    val dCopied get() = dec(D_COPIED)
    val dCopy get() = dec(D_COPY)
}