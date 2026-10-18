package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.lumen.coacervation.engine.host.LumenDrawableScene

internal typealias HostCardMaterialScene = LumenDrawableScene

internal class HostCardMaterialPicture : HostCardMaterialScene by LumenDrawableScene.create(hardware = false)
