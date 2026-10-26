package dev.unvoid.originceiler

import android.content.Context
import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CustomLockscreenClockPicker(
    state: UiState,
    model: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedCategory by rememberSaveable { mutableStateOf(LockscreenClockCatalog.CATEGORY_ALL) }

    val allStyles = remember(context) { LockscreenClockCatalog.getAllStyles(context) }
    val filteredStyles = remember(selectedCategory, allStyles) {
        if (selectedCategory == LockscreenClockCatalog.CATEGORY_ALL) {
            allStyles
        } else {
            allStyles.filter { it.category == selectedCategory }
        }
    }

    val categories = listOf(
        LockscreenClockCatalog.CATEGORY_ALL,
        LockscreenClockCatalog.CATEGORY_VIVO,
        LockscreenClockCatalog.CATEGORY_HYPEROS,
        LockscreenClockCatalog.CATEGORY_FLYME,
        LockscreenClockCatalog.CATEGORY_PIXEL
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Category Filter Chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                val count = when (category) {
                    LockscreenClockCatalog.CATEGORY_ALL -> allStyles.size
                    else -> allStyles.count { it.category == category }
                }
                val label = "$category ($count)"
                val isSelected = selectedCategory == category

                val chipBg by animateColorAsState(
                    targetValue = if (isSelected) OriginAccent else OriginCardBg,
                    label = "chipBg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else OriginTextSecondary,
                    label = "textColor"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(chipBg)
                        .clickable { selectedCategory = category }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Clock Previews Row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredStyles, key = { it.id }) { style ->
                ClockStyleCard(
                    style = style,
                    isSelected = state.customLockscreenClockStyle == style.id ||
                            state.customLockscreenClockStyle == style.key ||
                            (state.customLockscreenClockStyle.isBlank() && style.id == LockscreenClockCatalog.DEFAULT_STYLE_ID),
                    onClick = { model.setCustomLockscreenClockStyle(style.id) }
                )
            }
        }
    }
}

@Composable
private fun ClockStyleCard(
    style: LockscreenClockStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewBitmap = remember(style.previewAsset) {
        loadAssetBitmap(context, style.previewAsset)
    }

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) OriginAccent else Color.Transparent,
        label = "borderColor"
    )

    Column(
        modifier = modifier
            .width(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(OriginCardBg)
            .border(
                BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) OriginAccent else OriginDivider),
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Thumbnail Preview Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF141414)),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = style.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    if (style.id.startsWith("flyme.alive")) {
                        Text(
                            text = if (style.isVertical) "10\n30" else "10:30",
                            color = Color.White,
                            fontSize = if (style.isVertical) 34.sp else 24.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = if (style.isVertical) 34.sp else 24.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "8月24日 星期一",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp
                        )
                    } else {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = OriginAccent,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "12\n34",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 28.sp
                        )
                    }
                }
            }

            // Selection Check Badge
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(OriginAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Title and Category label
        Text(
            text = style.title,
            color = if (isSelected) OriginAccent else OriginTextPrimary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = style.category,
            color = OriginTextSecondary,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

private fun loadAssetBitmap(context: Context, assetPath: String): ImageBitmap? {
    if (assetPath.isBlank()) return null
    return runCatching {
        LockscreenClockRuntime.openAsset(context, assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
    }.getOrNull()
}

@Composable
internal fun CustomLockscreenClockColorPicker(
    state: UiState,
    model: MainViewModel,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val colorPresets = remember {
        listOf(
            0 to "Auto",
            0xFFFFFFFF.toInt() to "White",
            0xFF212121.toInt() to "Charcoal",
            0xFFFF6F00.toInt() to "Orange",
            0xFFFFB300.toInt() to "Amber",
            0xFF00E676.toInt() to "Mint",
            0xFF00E5FF.toInt() to "Cyan",
            0xFF2979FF.toInt() to "Blue",
            0xFFB388FF.toInt() to "Purple",
            0xFFFF4081.toInt() to "Pink",
            0xFFFF1744.toInt() to "Red"
        )
    }

    OriginCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Clock color",
                color = OriginTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Accent color for clock digits and labels",
                color = OriginTextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(colorPresets) { (colorInt, name) ->
                    val isSelected = state.customLockscreenClockColor == colorInt
                    val isAuto = colorInt == 0
                    val displayColor = if (isAuto) OriginAccent else Color(colorInt)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            model.setCustomLockscreenClockColor(colorInt)
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isAuto) OriginCardBg else displayColor)
                                .border(
                                    BorderStroke(
                                        if (isSelected) 2.5.dp else 1.dp,
                                        if (isSelected) (if (isAuto) OriginAccent else Color.White) else OriginDivider
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = name,
                                    tint = if (colorInt == 0xFFFFFFFF.toInt()) Color.Black else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else if (isAuto) {
                                Text(
                                    text = "A",
                                    color = OriginAccent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = name,
                            color = if (isSelected) OriginAccent else OriginTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
