/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.yunx.app.R
import com.yunx.app.ui.SnackbarController
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 支持开发页：展示赞助码（作者 / 协作者各一张，堆叠卡片左右滑动切换，可保存到相册）。
 * Material3 风格：渐变头部 + 堆叠卡片展示二维码 + 感谢语 + 保存按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 保存到相册成功状态（覆盖层内全局 Snackbar 可能被遮挡，用本地状态兜底反馈）
    var saved by remember { mutableStateOf(false) }
    // Android 9- 保存到公共 Pictures 需 WRITE_EXTERNAL_STORAGE 运行时授权
    var pendingPermission by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }
    // 系统返回键 → 返回设置页
    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("支持开发", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- 渐变欢迎头 ----------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.VolunteerActivism,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "支持开发",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "你的支持，是持续维护与更新的动力",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // ---------- 微信捐赠码：堆叠卡片（左右滑动切换） ----------
            //   两张码（作者 / 协作者）不做成上下两张：并排两张码像在催捐，堆成一副牌、滑一下换一张，
            //   观感克制些，也省一屏高度。当前是哪张由 SupportScreen 持有 —— 「保存到相册」要存它。
            var cardIndex by remember { mutableStateOf(0) }
            SponsorCardStack(
                cards = sponsorCards,
                currentIndex = cardIndex,
                onCurrentIndexChange = { cardIndex = it }
            )

            // ---------- 免责说明（委婉） ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VolunteerActivism,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "云析完全免费开源，所有功能无需捐赠即可正常使用。" +
                            "如果你觉得它帮到了你，愿意的话可以扫码表达一下心意，" +
                            "你的支持会成为持续维护与更新的动力～",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 21.sp
                    )
                }
            }

            // ---------- 感谢语 ----------
            Text(
                text = "感谢每一位支持者 ❤",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // ---------- 保存到相册 ----------
            Button(
                onClick = {
                    scope.launch {
                        // Android 9- 保存相册前检查并动态申请存储权限
                        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            true
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                            true
                        } else {
                            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            pendingPermission = deferred
                            withContext(Dispatchers.Main) {
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                            deferred.await()
                        }
                        if (!granted) {
                            SnackbarController.show("未授予存储权限，无法保存到相册")
                            return@launch
                        }
                        val current = sponsorCards[cardIndex]
                        val ok = withContext(Dispatchers.IO) {
                            saveQrToGallery(context, current.drawableRes, current.fileTag)
                        }
                        if (ok) saved = true
                        SnackbarController.show(if (ok) "已保存到相册（Pictures/YunX）" else "保存失败")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 说清存的是哪张：翻过卡片后用户需要知道这一下存的是谁的码
                Text(if (saved) "已保存到相册" else "保存 ${sponsorCards[cardIndex].role} 的二维码")
            }
            // 保存成功本地反馈（避免覆盖层遮挡全局 Snackbar 时无提示）
            if (saved) {
                Text(
                    text = "✓ 二维码已保存到 相册/Pictures/YunX",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 一张赞助码卡片。
 *
 * ★ 用「堆叠卡片」而不是上下排两张：并排两张码像在催捐，堆成一副牌、滑一下换一张，
 *   观感克制些，也省一屏高度（见 [SponsorCardStack]）。
 */
private data class SponsorCard(
    /** 是谁的码（作者 / 协作者）——两张码必须一眼分得清，存错人就白扫了 */
    val role: String,
    @DrawableRes val drawableRes: Int,
    /** 存到相册时的文件名标记 */
    val fileTag: String,
    /** 扫码提示 */
    val hint: String
)

/** 页面上的全部赞助码：作者在前（第一张就应该是它） */
private val sponsorCards = listOf(
    SponsorCard(
        role = "作者 CYQawa",
        drawableRes = R.drawable.weixin,
        fileTag = "cyqawa",
        hint = "保存二维码到相册后，打开微信「扫一扫」即可捐赠"
    ),
    SponsorCard(
        role = "协作者 tidain",
        drawableRes = R.drawable.sponsor_tidain_weixin,
        fileTag = "tidain",
        hint = "保存二维码到相册后，打开微信「扫一扫」即可捐赠"
    )
)

/** 松手时判定「甩一下也算换牌」的速度阈值（px/s）：快速一甩不必拖满距离阈值 */
private const val SWITCH_FLING_VELOCITY = 800f

/**
 * 换牌距离阈值：**取 [SWITCH_THRESHOLD] 与「牌宽 × [SWITCH_THRESHOLD_WIDTH_FRACTION]」里更小的那个**。
 *
 * ★ 为什么不只用固定值：88dp 在 1080p 手机上约 260px ≈ **屏宽的四分之一**，一次「随手一划」根本到不了，
 *   用户的原话就是「滑不动」。按牌宽取比例后，轻划约屏宽的 1/5 就能换牌（窄屏/大屏都自适应）。
 */
private val SWITCH_THRESHOLD = 88.dp
private const val SWITCH_THRESHOLD_WIDTH_FRACTION = 0.18f

/**
 * 换牌动画（滑出屏幕 → 从对侧绕回落位）的弹簧参数。
 *
 * ★ 用弹簧而不是 `tween`：`tween` 的起始速度恒为 0，手指还在动就松手时速度会**突然断掉**，
 *   看起来就是一「顿」。弹簧 + 把手指速度当初始速度传进去（`initialVelocity`）才接得上。
 * ★ **偏软 + 略欠阻尼**（`stiffness` 120 比 `StiffnessLow`(200) 还软、`dampingRatio` 0.95 留一点点回弹）：
 *   卡片被甩出去、绕回来时才带惯性，最后轻轻一顿停住；太硬会显得「弹一下就到位」。
 * ★ `visibilityThreshold = 1f`：像素量级下默认的 0.01f 会让尾巴拖很久。
 */
private val SWITCH_SPRING = spring<Float>(
    dampingRatio = 0.95f,
    stiffness = 120f,
    visibilityThreshold = 1f
)

/** 取消滑动（没到阈值）时弹回原位用的弹簧：比换牌紧一点，回弹干脆 */
private val CANCEL_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 1f
)

/**
 * **逆着底牌那一侧**滑动时，底牌前移要压后一段时间（占跟手进度的比例）。
 *
 * ★ 为什么需要：底牌在右边时你若往右滑，飞出去的顶牌和往前挪的底牌会**对穿**（一个往右一个往左，
 *   视觉上像两张牌互相穿过去）。让底牌先等顶牌走掉再前移，就顺了。
 *   顺着底牌那一侧滑（底牌在右、往左滑）时不需要这个延迟，直接跟手最自然。
 */
private const val REVERSAL_APPROACH_DELAY = 0.45f

/**
 * 底牌相对顶牌的**露出量**（横向）与**倾斜角**。
 *
 * ★ 这两个值决定「堆叠」看不看得出来，别再调小：早先只露 16dp、不倾斜，用户的原话是
 *   「看不出有堆叠效果，就旁边一点点」。
 * ★ 注意「露出量」是位移，**实际看得见的边条要减掉缩放带来的内缩**：
 *   可见宽度 ≈ 露出量 − (1 − 缩放) × 卡片宽 / 2。
 */
private val BACK_PEEK = 42.dp
private const val BACK_TILT_DEG = 4f

/** 底牌的静止缩放（近大远小：底牌小一圈、顶牌满血）。缩太多会把上面那条「可见边条」吃掉 */
private const val BACK_SCALE = 0.90f

/**
 * 牌堆左右各留出的内边距。
 *
 * ★ 顶牌要**居中**（用户要求「上方卡牌在中间」），同时底牌又要从一侧露出来 —— 那截露出必须落在
 *   屏幕内，否则会被边缘切掉（父级只有 16dp 内边距）。靠这个内边距腾地方：牌因此比容器窄一点
 *   （略小一圈），换来「顶牌居中 + 底牌探出的边条完整可见」。
 */
private val CARD_INSET = 12.dp

/**
 * 赞助码堆叠卡片（**循环轮播**）：当前这张在最前，另一张缩到 [BACK_SCALE]、往一侧露出 [BACK_PEEK]、
 * 再倾斜一点（像 Android / iOS 的堆叠后台）。
 *
 * **切换动画**：顶牌顺着滑动方向**滑出屏幕**，再从对侧**绕回并落到后位**；底牌同时前移放大到满血。
 * 里外里是一副牌在循环转，不是「滑一下又弹回来」。
 *
 * 实现要点（改之前先读，很容易改出跳变）：
 * 1. 只有一个驱动量 `travel`（**带符号的像素行进量**）。用它而不是「进度百分比」是因为三段行程
 *    的像素长度不同，按百分比驱动会让速度在段与段之间跳一下；按像素驱动则全程匀速、顺滑。
 *    位移公式因此可以写得极简（`W` = 牌堆宽度，`P` = 露出量）：
 *    `t ≤ W` 时 `x = dir * t`（跟手 + 滑出屏幕是同一段直线），`t > W` 时 `x = dir * (t - 2W)`
 *    （在屏幕外从另一侧绕回），终点 `t = 2W - P` 正好是后位。
 * 2. **绕回的「瞬移」必须发生在屏幕外**（`+W ↔ -W`，两侧都完全出画）—— 所以整段动画是一条
 *    连续曲线，不需要任何淡入淡出来遮丑。
 * 3. 换牌结束那一刻的视觉状态，**必须恰好等于**「`travel=0` 且换了牌」的状态：
 *    位移/缩放/前后感/倾斜/层序五项都得对上，然后**同一帧内**改 `currentIndex` + `backSide` +
 *    `travel=0`，才不会闪。
 * 4. 层序靠 [zIndex] 翻转（不是靠调换组合顺序）：退场牌要到 `t ≥ W`（完全出画）才沉到后面去，
 *    提前翻会看见两张牌互相盖一下。
 * 5. 露出的那一侧（`backSide`）每次换牌都翻到**绕回来的那一侧**：牌从哪边回来就停在哪边，
 *    下一次再从它对面绕出去。别固定成一边。
 * 6. 松手后**必须把手速当初始速度传给弹簧**（`initialVelocity = velocity`），否则手指还在动时
 *    松手会「顿」一下。
 */
@Composable
private fun SponsorCardStack(
    cards: List<SponsorCard>,
    currentIndex: Int,
    onCurrentIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val peekPx = with(density) { BACK_PEEK.toPx() }

    // 底牌停在哪一侧（-1 左 / +1 右）。**初始 +1**：一进页面就是堆叠态 —— 顶牌**居中**，
    // 底牌在右边明显露出来（带倾斜 + 缩小一圈），一眼能看出下面还压着一张牌。
    // 每次换牌后翻到「绕回来的那一侧」：往左滑 → 牌从左飞出、从右绕回，停在右边（与初始一致）；
    // 往右滑 → 停在左边「接住」从左边绕回来的那张。
    var backSide by remember { mutableFloatStateOf(1f) }
    // 手指原始位移（不夹）：判定换牌用它。跟手时 travel 最多到 ±牌宽（滑出屏幕为止），
    // 手指拖得更远时它还在继续累加 —— 判定要用真实位移，所以两者分开记
    var fingerX by remember { mutableFloatStateOf(0f) }
    /**
     * 行进量（px，带符号）——**唯一的驱动量**，含义见 KDoc 第 1 条。
     *
     * ★ 用普通 state 而不是 `Animatable`：`Animatable` 只能在协程里改（`snapTo`），于是「每次拖动增量
     *   起一个协程去 snapTo」——快滑时排在后面的 `snapTo` 会在松手之后才执行，**把换牌动画直接打断**，
     *   而换牌（`onCurrentIndexChange`）写在动画之后 → 动画被取消就永远不执行，表现就是
     *   「滑得快就切换失败」。普通 state 可以同步写，配合下面的 `animJob` 显式取消，就没有这个竞态。
     */
    var travel by remember { mutableFloatStateOf(0f) }
    // 松手后那段动画的协程：新的一次拖动要显式取消它，否则两个写入源会互相打架
    var animJob by remember { mutableStateOf<Job?>(null) }
    // 牌堆宽度（px）：绕回要跑到屏幕外，得知道有多宽
    var stackWidthPx by remember { mutableIntStateOf(0) }

    val widthPx = stackWidthPx.coerceAtLeast(1)
    /**
     * 换牌距离阈值（见 [SWITCH_THRESHOLD] 的说明）。
     *
     * ★ 只能在 `onDragStopped` 与渲染里读它；**不要**在下面那个被 `rememberDraggableState` 存下来的
     *   拖动回调里读 —— 那个 lambda 只在首次组合时捕获一次，那时牌宽还没测到，拿到的会是 1 对应的值。
     *   拖动回调需要宽度时直接读 `stackWidthPx`（state 取值是实时的）。
     */
    val switchThresholdPx = min(with(density) { SWITCH_THRESHOLD.toPx() }, widthPx * SWITCH_THRESHOLD_WIDTH_FRACTION)
    val t = abs(travel)
    val dir = if (travel < 0f) -1f else 1f
    // 换牌动画的终点：绕一圈回到后位（`2W - P`）
    val endTravelPx = (2f * widthPx - peekPx).coerceAtLeast(1f)
    // 拖拽进度 0..1（到阈值即 1）：只用来驱动「谁在前谁在后」那套形变
    val dragProgress = (t / switchThresholdPx).coerceIn(0f, 1f)
    /**
     * 牌堆**不再横向挪动**：顶牌静止时必须落在正中（「上方卡牌在中间」）。
     *
     * ★ 早先这里有一份 `deckShiftPx`（把整副牌朝「底牌露出的反方向」挪半个露出量），目的是让底牌
     *   探出的那截不被屏幕边缘切掉 —— 代价是顶牌整体偏了半个露出量，看起来不在中间。
     * ★ 现在改由 [CARD_INSET] 腾地方：牌比容器窄一圈，顶牌居中时底牌探出的边条仍在屏幕内，
     *   所以挪动量恒为 0 —— 直接删掉，留着常量只会让人以为还有这段逻辑。
     */

    val otherIndex = (currentIndex + 1) % cards.size
    // 退场牌（当前顶牌）的位移：跟手 + 滑出屏幕 → 屏幕外换边 → 从对侧绕回后位
    val exitX = dir * (if (t <= widthPx) t else t - 2f * widthPx)
    // 退场牌是否已彻底出画：到了才翻转层序，否则会看见两张牌互相盖一下
    val exitOffScreen = t >= widthPx
    // 底牌的前移进度：顺着它那一侧滑时直接跟手；逆着滑（会与顶牌对穿）时先等顶牌走掉，
    // 所以整体压后 REVERSAL_APPROACH_DELAY 那段（见该常量的说明）
    val approachProgress = if (dir == backSide) {
        ((dragProgress - REVERSAL_APPROACH_DELAY) / (1f - REVERSAL_APPROACH_DELAY)).coerceIn(0f, 1f)
    } else {
        dragProgress
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { stackWidthPx = it.width }
                // ★ 左右内缩 [CARD_INSET]：两张牌都比容器窄一圈，于是「顶牌居中 + 底牌从一侧露出」
                //   两者可以同时成立，而露出的边条仍在屏幕内。拖动热区随之等于卡片区域。
                .padding(horizontal = CARD_INSET)
                .draggable(
                    orientation = Orientation.Horizontal,
                    // 同步累加：不启协程（原因见 travel 的说明）
                    state = rememberDraggableState { delta ->
                        fingerX += delta
                        // ★ 跟手**不再夹在阈值内**：卡片一路跟着手指走（最远到「完全滑出屏幕」，即 ±牌宽），
                        //   松手时才决定换不换牌。以前夹在 ±阈值内，拖过阈值后卡片就停住不动了 ——
                        //   手感正是用户说的「滑不动」。
                        // ★ 这里读 stackWidthPx（state，取值实时）；**不要**读上面的 switchThresholdPx ——
                        //   这个 lambda 只在首次组合时捕获一次，那时牌宽还没测到，会拿到错误的阈值。
                        val cap = stackWidthPx.coerceAtLeast(1).toFloat()
                        travel = fingerX.coerceIn(-cap, cap)
                    },
                    /**
                     * 重新抓住卡片。
                     *
                     * ★ 动画**已经越过阈值**时（`|travel| > 换牌阈值`：底牌已前移到顶位、`zIndex` 也翻过了）
                     *   不能再「接着拖」—— 此刻视觉上的顶牌其实已经是下一张，就地接管会出现
                     *   「手指按着上面那张、跟手动的是另一张」。所以先把这次换牌**立即落定**
                     *   （换牌 + 回到 `travel = 0` 的静止态），手指再拖的就是新顶牌，行为与视觉一致。
                     *   被取消的那段动画停在 `animate` 里，**不会再执行它后面的换牌**，不会重复换。
                     * ★ 还没到阈值时照旧：取消动画、从当前位移接着拖 —— 这时可以把牌拖回去取消换牌。
                     */
                    onDragStarted = {
                        if (animJob?.isActive == true) {
                            animJob?.cancel()
                            if (abs(travel) > switchThresholdPx) {
                                onCurrentIndexChange(otherIndex)
                                // 动画方向就是 travel 的符号；换完牌底牌翻到「绕回来的那一侧」
                                backSide = if (travel < 0f) 1f else -1f
                                travel = 0f
                            }
                        }
                        animJob = null
                        fingerX = travel
                    },
                    onDragStopped = { velocity ->
                        val passed = abs(fingerX) > switchThresholdPx ||
                            abs(velocity) > SWITCH_FLING_VELOCITY
                        // 方向优先取手指位移，纯靠甩速触发时退回甩速方向
                        val dragDir = when {
                            fingerX != 0f -> sign(fingerX)
                            velocity != 0f -> sign(velocity)
                            else -> 1f
                        }
                        val startX = travel
                        fingerX = 0f
                        animJob = scope.launch {
                            if (passed) {
                                // 接着手速走，别在松手那一刻把速度归零
                                animate(
                                    initialValue = startX,
                                    targetValue = dragDir * endTravelPx,
                                    initialVelocity = velocity,
                                    animationSpec = SWITCH_SPRING
                                ) { value, _ -> travel = value }
                                // 此刻的视觉已经等于「travel=0 且换了牌」：三项一起改，同一帧生效
                                onCurrentIndexChange(otherIndex)
                                backSide = -dragDir
                                travel = 0f
                            } else {
                                animate(
                                    initialValue = startX,
                                    targetValue = 0f,
                                    initialVelocity = velocity,
                                    animationSpec = CANCEL_SPRING
                                ) { value, _ -> travel = value }
                            }
                        }
                    }
                )
        ) {
            // 底牌（即将前移的那张）
            SponsorCodeCard(
                card = cards[otherIndex],
                backness = 1f - approachProgress,
                contentDescriptionSuffix = "（下一张）",
                modifier = Modifier
                    .zIndex(if (exitOffScreen) 1f else 0f)
                    .graphicsLayer {
                        val scale = BACK_SCALE + (1f - BACK_SCALE) * approachProgress
                        scaleX = scale
                        scaleY = scale
                        translationX = backSide * peekPx * (1f - approachProgress)
                        rotationZ = backSide * BACK_TILT_DEG * (1f - approachProgress)
                    }
            )
            // 顶牌（跟手 / 正在退场的那张）
            SponsorCodeCard(
                card = cards[currentIndex],
                backness = dragProgress,
                contentDescriptionSuffix = "",
                modifier = Modifier
                    .zIndex(if (exitOffScreen) 0f else 1f)
                    .graphicsLayer {
                        val scale = 1f - (1f - BACK_SCALE) * dragProgress
                        scaleX = scale
                        scaleY = scale
                        translationX = exitX
                        rotationZ = -dir * BACK_TILT_DEG * dragProgress
                    }
            )
        }

        // 页码点：堆叠本身看不出总数，用它说明「一共两张、现在在第几张」
        if (cards.size > 1) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                cards.indices.forEach { index ->
                    val active = index == currentIndex
                    Box(
                        modifier = Modifier
                            .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                            .background(
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                                shape = CircleShape
                            )
                    )
                }
            }
            Text(
                text = "左右滑动切换",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 单张赞助码卡片：标题 + 角色标签 + 二维码 + 扫码提示。
 *
 * [backness] 是**前后关系的连续插值**（1 = 完全在底层）：它同时决定底色（暗一档）、描边与投影 ——
 * 三者一起变，用户才分得清「这是两张牌」。用插值而不是 `if (isFront)` 是因为：换牌瞬间若颜色
 * 硬切，卡片会闪一下（拖动过程中它也在从底变顶）。
 */
@Composable
private fun SponsorCodeCard(
    card: SponsorCard,
    backness: Float,
    contentDescriptionSuffix: String,
    modifier: Modifier = Modifier
) {
    val frontness = (1f - backness).coerceIn(0f, 1f)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = lerp(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.surfaceContainerLow,
                frontness
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = lerp(12.dp, 2.dp, frontness)),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f * backness)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "微信扫码捐赠",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 角色标签：两张码靠它区分
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = card.role,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 二维码图片（白底圆角卡片内展示）
            Surface(
                modifier = Modifier.size(240.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Image(
                    painter = painterResource(card.drawableRes),
                    contentDescription = "${card.role} 的微信捐赠码$contentDescriptionSuffix",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = card.hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 将内置的赞助码解码为 Bitmap 并保存到系统相册（Android 10+ 走 MediaStore，无需权限）。
 *
 * [drawableRes] / [fileTag] 由当前展示的那张卡片决定：页面上有作者与协作者两张码，
 * 存错人等于让用户拿着别人的码去扫。
 */
private fun saveQrToGallery(context: Context, @DrawableRes drawableRes: Int, fileTag: String): Boolean = runCatching {
    val bitmap: Bitmap = BitmapFactory.decodeResource(context.resources, drawableRes)
        ?: return@runCatching false
    val fileName = "yunx_wechat_qr_${fileTag}_${System.currentTimeMillis()}.jpg"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YunX")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: return@runCatching false
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        true
    } else {
        // Android 10 以下：写入公共 Pictures 目录（需 WRITE_EXTERNAL_STORAGE 权限）
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        true
    }
}.getOrDefault(false)