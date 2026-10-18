package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.host.LumenDrawableScene

@RequiresApi(29)
internal class HostCardMaterialSceneApi29(scale: Float = 1f) :
    HostCardMaterialScene by LumenDrawableScene.create(hardware = true, scale = scale)
