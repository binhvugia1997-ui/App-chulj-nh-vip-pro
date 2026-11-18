package com.gia.aicomposer.pose

/**
 * Thư viện 30 dáng mẫu tự vẽ tay.
 *
 * Mỗi dáng được khai báo bằng một bộ xương cơ sở rồi chỉnh vài khớp, toạ độ 0..1
 * trong khung riêng của dáng (aspect = width / height của khung đó).
 * FULL_BODY có đủ chân; HALF_BODY dừng ở hông nên overlay tự bỏ các xương chân.
 *
 * Logic tham khảo ý tưởng từ repo pose-suggester, viết lại hoàn toàn theo kiến trúc app.
 */
object PoseLibrary {

    private val FULL_BODY_BASE: Map<Joint, PosePoint> = mapOf(
        Joint.HEAD to PosePoint(0.500f, 0.090f),
        Joint.NECK to PosePoint(0.500f, 0.185f),
        Joint.L_SHOULDER to PosePoint(0.435f, 0.205f),
        Joint.R_SHOULDER to PosePoint(0.565f, 0.205f),
        Joint.L_ELBOW to PosePoint(0.400f, 0.320f),
        Joint.R_ELBOW to PosePoint(0.600f, 0.320f),
        Joint.L_WRIST to PosePoint(0.395f, 0.440f),
        Joint.R_WRIST to PosePoint(0.605f, 0.440f),
        Joint.PELVIS to PosePoint(0.500f, 0.475f),
        Joint.L_HIP to PosePoint(0.455f, 0.490f),
        Joint.R_HIP to PosePoint(0.545f, 0.490f),
        Joint.L_KNEE to PosePoint(0.450f, 0.700f),
        Joint.R_KNEE to PosePoint(0.550f, 0.700f),
        Joint.L_ANKLE to PosePoint(0.440f, 0.925f),
        Joint.R_ANKLE to PosePoint(0.560f, 0.925f),
    )

    private val HALF_BODY_BASE: Map<Joint, PosePoint> = mapOf(
        Joint.HEAD to PosePoint(0.500f, 0.120f),
        Joint.NECK to PosePoint(0.500f, 0.260f),
        Joint.L_SHOULDER to PosePoint(0.400f, 0.290f),
        Joint.R_SHOULDER to PosePoint(0.600f, 0.290f),
        Joint.L_ELBOW to PosePoint(0.340f, 0.460f),
        Joint.R_ELBOW to PosePoint(0.660f, 0.460f),
        Joint.L_WRIST to PosePoint(0.330f, 0.620f),
        Joint.R_WRIST to PosePoint(0.670f, 0.620f),
        Joint.PELVIS to PosePoint(0.500f, 0.860f),
        Joint.L_HIP to PosePoint(0.440f, 0.880f),
        Joint.R_HIP to PosePoint(0.560f, 0.880f),
    )

    private class Draft(category: PoseCategory) {
        val points = LinkedHashMap<Joint, PosePoint>().apply {
            putAll(if (category == PoseCategory.HALF_BODY) HALF_BODY_BASE else FULL_BODY_BASE)
        }

        fun at(joint: Joint, x: Float, y: Float) {
            points[joint] = PosePoint(x, y)
        }

        fun fluent(vararg values: Any): Draft {
            var index = 0
            while (index + 2 < values.size) {
                val joint = values[index] as Joint
                val x = values[index + 1] as Float
                val y = values[index + 2] as Float
                at(joint, x, y)
                index += 3
            }
            return this
        }
    }

    private val GROUND_ONLY: Set<Affordance> = setOf(Affordance.OPEN_GROUND)
    private val NO_ENCOURAGED: Set<Affordance> = emptySet()

    private fun pose(
        id: String,
        name: String,
        category: PoseCategory,
        aspect: Float,
        required: Set<Affordance> = emptySet(),
        encouraged: Set<Affordance> = GROUND_ONLY,
        block: Draft.() -> Unit,
    ): PoseTemplate {
        val draft = Draft(category).apply(block)
        return PoseTemplate(
            id = id,
            displayName = name,
            category = category,
            aspect = aspect,
            requiredAffordances = required,
            encouragedAffordances = encouraged,
            joints = draft.points.toMap(),
        )
    }

    val poses: List<PoseTemplate> = listOf(
        // ---------- FULL_BODY (20) ----------
        pose("fb_stand", "Đứng tự nhiên", PoseCategory.FULL_BODY, 0.46f) { },
        pose("fb_hand_on_hip", "Tay chống hông", PoseCategory.FULL_BODY, 0.46f) {
            at(Joint.R_ELBOW, 0.645f, 0.330f)
            at(Joint.R_WRIST, 0.515f, 0.470f)
        },
        pose("fb_arms_crossed", "Khoanh tay", PoseCategory.FULL_BODY, 0.46f) {
            at(Joint.L_ELBOW, 0.405f, 0.330f)
            at(Joint.R_ELBOW, 0.595f, 0.330f)
            at(Joint.L_WRIST, 0.565f, 0.400f)
            at(Joint.R_WRIST, 0.440f, 0.400f)
        },
        pose("fb_walk", "Bước đi", PoseCategory.FULL_BODY, 0.55f) {
            at(Joint.L_KNEE, 0.400f, 0.710f)
            at(Joint.L_ANKLE, 0.325f, 0.910f)
            at(Joint.R_KNEE, 0.585f, 0.710f)
            at(Joint.R_ANKLE, 0.675f, 0.900f)
            at(Joint.L_ELBOW, 0.375f, 0.330f)
            at(Joint.L_WRIST, 0.335f, 0.450f)
            at(Joint.R_ELBOW, 0.630f, 0.330f)
            at(Joint.R_WRIST, 0.685f, 0.440f)
        },
        pose("fb_jump", "Nhảy lên", PoseCategory.FULL_BODY, 0.52f) {
            fluent(
                Joint.HEAD, 0.500f, 0.050f,
                Joint.NECK, 0.500f, 0.145f,
                Joint.L_SHOULDER, 0.440f, 0.165f,
                Joint.R_SHOULDER, 0.560f, 0.165f,
                Joint.L_ELBOW, 0.360f, 0.170f,
                Joint.R_ELBOW, 0.640f, 0.170f,
                Joint.L_WRIST, 0.300f, 0.080f,
                Joint.R_WRIST, 0.700f, 0.080f,
                Joint.PELVIS, 0.500f, 0.440f,
                Joint.L_HIP, 0.455f, 0.455f,
                Joint.R_HIP, 0.545f, 0.455f,
                Joint.L_KNEE, 0.410f, 0.600f,
                Joint.R_KNEE, 0.590f, 0.600f,
                Joint.L_ANKLE, 0.360f, 0.720f,
                Joint.R_ANKLE, 0.640f, 0.720f,
            )
        },
        pose(
            "fb_lean_wall",
            "Tựa tường",
            PoseCategory.FULL_BODY,
            0.50f,
            required = setOf(Affordance.WALL),
            encouraged = NO_ENCOURAGED,
        ) {
            fluent(
                Joint.HEAD, 0.455f, 0.090f,
                Joint.NECK, 0.445f, 0.185f,
                Joint.L_SHOULDER, 0.375f, 0.205f,
                Joint.R_SHOULDER, 0.510f, 0.205f,
                Joint.L_ELBOW, 0.360f, 0.330f,
                Joint.L_WRIST, 0.420f, 0.440f,
                Joint.R_ELBOW, 0.600f, 0.300f,
                Joint.R_WRIST, 0.780f, 0.240f,
                Joint.PELVIS, 0.470f, 0.475f,
                Joint.L_HIP, 0.430f, 0.495f,
                Joint.R_HIP, 0.510f, 0.490f,
                Joint.L_KNEE, 0.420f, 0.700f,
                Joint.R_KNEE, 0.530f, 0.700f,
                Joint.L_ANKLE, 0.400f, 0.925f,
                Joint.R_ANKLE, 0.560f, 0.925f,
            )
        },
        pose(
            "fb_sit_chair",
            "Ngồi ghế",
            PoseCategory.FULL_BODY,
            0.62f,
            required = setOf(Affordance.CHAIR),
            encouraged = NO_ENCOURAGED,
        ) {
            fluent(
                Joint.HEAD, 0.500f, 0.170f,
                Joint.NECK, 0.500f, 0.255f,
                Joint.L_SHOULDER, 0.440f, 0.275f,
                Joint.R_SHOULDER, 0.560f, 0.275f,
                Joint.L_ELBOW, 0.380f, 0.400f,
                Joint.L_WRIST, 0.360f, 0.520f,
                Joint.R_ELBOW, 0.620f, 0.400f,
                Joint.R_WRIST, 0.640f, 0.520f,
                Joint.PELVIS, 0.500f, 0.550f,
                Joint.L_HIP, 0.455f, 0.565f,
                Joint.R_HIP, 0.545f, 0.565f,
                Joint.L_KNEE, 0.400f, 0.600f,
                Joint.R_KNEE, 0.600f, 0.600f,
                Joint.L_ANKLE, 0.385f, 0.900f,
                Joint.R_ANKLE, 0.615f, 0.900f,
            )
        },
        pose(
            "fb_sit_cross_chair",
            "Ngồi vắt chéo chân",
            PoseCategory.FULL_BODY,
            0.62f,
            required = setOf(Affordance.CHAIR),
            encouraged = NO_ENCOURAGED,
        ) {
            fluent(
                Joint.HEAD, 0.505f, 0.170f,
                Joint.NECK, 0.505f, 0.255f,
                Joint.L_ELBOW, 0.370f, 0.420f,
                Joint.L_WRIST, 0.450f, 0.580f,
                Joint.R_ELBOW, 0.630f, 0.420f,
                Joint.R_WRIST, 0.550f, 0.600f,
                Joint.PELVIS, 0.500f, 0.550f,
                Joint.L_KNEE, 0.380f, 0.620f,
                Joint.L_ANKLE, 0.550f, 0.660f,
                Joint.R_KNEE, 0.620f, 0.600f,
                Joint.R_ANKLE, 0.600f, 0.880f,
            )
        },
        pose(
            "fb_lean_table",
            "Tựa bàn",
            PoseCategory.FULL_BODY,
            0.60f,
            required = setOf(Affordance.TABLE),
            encouraged = NO_ENCOURAGED,
        ) {
            fluent(
                Joint.HEAD, 0.470f, 0.160f,
                Joint.NECK, 0.465f, 0.250f,
                Joint.L_SHOULDER, 0.400f, 0.270f,
                Joint.R_SHOULDER, 0.530f, 0.270f,
                Joint.L_ELBOW, 0.350f, 0.420f,
                Joint.L_WRIST, 0.330f, 0.580f,
                Joint.R_ELBOW, 0.590f, 0.400f,
                Joint.R_WRIST, 0.700f, 0.520f,
                Joint.PELVIS, 0.490f, 0.530f,
                Joint.L_KNEE, 0.450f, 0.720f,
                Joint.R_KNEE, 0.560f, 0.720f,
                Joint.L_ANKLE, 0.430f, 0.930f,
                Joint.R_ANKLE, 0.600f, 0.930f,
            )
        },
        pose(
            "fb_hand_on_table",
            "Một tay trên bàn",
            PoseCategory.FULL_BODY,
            0.52f,
            required = setOf(Affordance.TABLE),
            encouraged = NO_ENCOURAGED,
        ) {
            at(Joint.L_ELBOW, 0.390f, 0.300f)
            at(Joint.L_WRIST, 0.300f, 0.500f)
            at(Joint.R_ELBOW, 0.620f, 0.340f)
            at(Joint.R_WRIST, 0.640f, 0.460f)
        },
        pose(
            "fb_under_tree",
            "Dưới tán cây",
            PoseCategory.FULL_BODY,
            0.50f,
            required = setOf(Affordance.TREE),
            encouraged = NO_ENCOURAGED,
        ) {
            at(Joint.L_ELBOW, 0.380f, 0.300f)
            at(Joint.L_WRIST, 0.300f, 0.200f)
            at(Joint.R_ELBOW, 0.620f, 0.300f)
            at(Joint.R_WRIST, 0.700f, 0.200f)
        },
        pose(
            "fb_beside_car",
            "Tựa xe",
            PoseCategory.FULL_BODY,
            0.55f,
            required = setOf(Affordance.CAR),
            encouraged = NO_ENCOURAGED,
        ) {
            fluent(
                Joint.HEAD, 0.450f, 0.100f,
                Joint.NECK, 0.440f, 0.195f,
                Joint.L_SHOULDER, 0.380f, 0.215f,
                Joint.R_SHOULDER, 0.510f, 0.215f,
                Joint.L_ELBOW, 0.355f, 0.340f,
                Joint.L_WRIST, 0.400f, 0.455f,
                Joint.R_ELBOW, 0.610f, 0.330f,
                Joint.R_WRIST, 0.720f, 0.440f,
                Joint.PELVIS, 0.470f, 0.480f,
                Joint.L_KNEE, 0.400f, 0.700f,
                Joint.L_ANKLE, 0.500f, 0.920f,
                Joint.R_KNEE, 0.560f, 0.700f,
                Joint.R_ANKLE, 0.600f, 0.920f,
            )
        },
        pose("fb_crouch", "Ngồi xổm", PoseCategory.FULL_BODY, 0.58f) {
            fluent(
                Joint.HEAD, 0.500f, 0.200f,
                Joint.NECK, 0.500f, 0.290f,
                Joint.L_SHOULDER, 0.440f, 0.310f,
                Joint.R_SHOULDER, 0.560f, 0.310f,
                Joint.L_ELBOW, 0.380f, 0.420f,
                Joint.L_WRIST, 0.360f, 0.540f,
                Joint.R_ELBOW, 0.620f, 0.420f,
                Joint.R_WRIST, 0.640f, 0.540f,
                Joint.PELVIS, 0.500f, 0.600f,
                Joint.L_KNEE, 0.360f, 0.700f,
                Joint.R_KNEE, 0.640f, 0.700f,
                Joint.L_ANKLE, 0.340f, 0.900f,
                Joint.R_ANKLE, 0.660f, 0.900f,
            )
        },
        pose("fb_look_back", "Ngoảnh lại", PoseCategory.FULL_BODY, 0.46f) {
            at(Joint.HEAD, 0.420f, 0.095f)
            at(Joint.NECK, 0.480f, 0.190f)
            at(Joint.L_SHOULDER, 0.400f, 0.210f)
            at(Joint.R_SHOULDER, 0.560f, 0.200f)
            at(Joint.L_WRIST, 0.340f, 0.450f)
            at(Joint.R_WRIST, 0.600f, 0.460f)
        },
        pose("fb_arms_wide", "Tay mở rộng", PoseCategory.FULL_BODY, 0.80f) {
            at(Joint.L_ELBOW, 0.300f, 0.260f)
            at(Joint.R_ELBOW, 0.700f, 0.260f)
            at(Joint.L_WRIST, 0.120f, 0.240f)
            at(Joint.R_WRIST, 0.880f, 0.240f)
        },
        pose("fb_hands_up", "Tay giơ cao", PoseCategory.FULL_BODY, 0.48f) {
            at(Joint.L_ELBOW, 0.420f, 0.280f)
            at(Joint.R_ELBOW, 0.580f, 0.280f)
            at(Joint.L_WRIST, 0.440f, 0.100f)
            at(Joint.R_WRIST, 0.560f, 0.100f)
        },
        pose("fb_one_leg", "Tạo dáng một chân", PoseCategory.FULL_BODY, 0.55f) {
            at(Joint.R_KNEE, 0.620f, 0.660f)
            at(Joint.R_ANKLE, 0.560f, 0.740f)
            at(Joint.L_WRIST, 0.280f, 0.300f)
            at(Joint.R_WRIST, 0.740f, 0.320f)
        },
        pose("fb_stride", "Sải bước tự tin", PoseCategory.FULL_BODY, 0.62f) {
            fluent(
                Joint.L_KNEE, 0.370f, 0.710f,
                Joint.L_ANKLE, 0.300f, 0.920f,
                Joint.R_KNEE, 0.620f, 0.710f,
                Joint.R_ANKLE, 0.700f, 0.920f,
                Joint.L_ELBOW, 0.380f, 0.340f,
                Joint.L_WRIST, 0.280f, 0.360f,
                Joint.R_ELBOW, 0.620f, 0.340f,
                Joint.R_WRIST, 0.720f, 0.360f,
            )
        },
        pose("fb_squat", "Squat nhẹ", PoseCategory.FULL_BODY, 0.60f) {
            fluent(
                Joint.HEAD, 0.500f, 0.190f,
                Joint.NECK, 0.500f, 0.280f,
                Joint.L_ELBOW, 0.380f, 0.440f,
                Joint.L_WRIST, 0.400f, 0.550f,
                Joint.R_ELBOW, 0.620f, 0.440f,
                Joint.R_WRIST, 0.600f, 0.550f,
                Joint.PELVIS, 0.500f, 0.620f,
                Joint.L_KNEE, 0.400f, 0.720f,
                Joint.R_KNEE, 0.600f, 0.720f,
                Joint.L_ANKLE, 0.420f, 0.920f,
                Joint.R_ANKLE, 0.580f, 0.920f,
            )
        },
        pose("fb_point", "Chỉ tay về phía trước", PoseCategory.FULL_BODY, 0.70f) {
            at(Joint.R_ELBOW, 0.660f, 0.260f)
            at(Joint.R_WRIST, 0.850f, 0.220f)
            at(Joint.L_WRIST, 0.430f, 0.440f)
        },

        // ---------- HALF_BODY (10) ----------
        pose("hb_portrait", "Chân dung cơ bản", PoseCategory.HALF_BODY, 0.70f) { },
        pose("hb_hand_cheek", "Tay chạm má", PoseCategory.HALF_BODY, 0.70f) {
            at(Joint.R_ELBOW, 0.680f, 0.450f)
            at(Joint.R_WRIST, 0.560f, 0.200f)
        },
        pose("hb_arms_crossed", "Khoanh tay nửa người", PoseCategory.HALF_BODY, 0.75f) {
            at(Joint.L_ELBOW, 0.360f, 0.440f)
            at(Joint.R_ELBOW, 0.640f, 0.440f)
            at(Joint.L_WRIST, 0.575f, 0.520f)
            at(Joint.R_WRIST, 0.430f, 0.500f)
        },
        pose("hb_hand_hair", "Vuốt tóc", PoseCategory.HALF_BODY, 0.70f) {
            at(Joint.L_ELBOW, 0.320f, 0.420f)
            at(Joint.L_WRIST, 0.430f, 0.150f)
        },
        pose("hb_look_over_shoulder", "Ngoái nhìn qua vai", PoseCategory.HALF_BODY, 0.72f) {
            at(Joint.HEAD, 0.430f, 0.120f)
            at(Joint.NECK, 0.470f, 0.260f)
            at(Joint.L_SHOULDER, 0.370f, 0.290f)
            at(Joint.R_SHOULDER, 0.560f, 0.280f)
            at(Joint.L_WRIST, 0.300f, 0.580f)
            at(Joint.R_WRIST, 0.700f, 0.600f)
        },
        pose("hb_chin_rest", "Tay đỡ cằm", PoseCategory.HALF_BODY, 0.72f) {
            at(Joint.L_ELBOW, 0.350f, 0.470f)
            at(Joint.L_WRIST, 0.460f, 0.230f)
            at(Joint.R_WRIST, 0.640f, 0.800f)
        },
        pose("hb_peace", "Giơ tay chữ V", PoseCategory.HALF_BODY, 0.75f) {
            at(Joint.R_ELBOW, 0.680f, 0.420f)
            at(Joint.R_WRIST, 0.620f, 0.160f)
        },
        pose("hb_hands_pocket", "Tay trong túi", PoseCategory.HALF_BODY, 0.80f) {
            at(Joint.L_ELBOW, 0.350f, 0.460f)
            at(Joint.R_ELBOW, 0.650f, 0.460f)
            at(Joint.L_WRIST, 0.420f, 0.800f)
            at(Joint.R_WRIST, 0.580f, 0.800f)
        },
        pose("hb_lean_forward", "Nghiêng người tới", PoseCategory.HALF_BODY, 0.72f) {
            fluent(
                Joint.HEAD, 0.470f, 0.120f,
                Joint.NECK, 0.490f, 0.260f,
                Joint.L_SHOULDER, 0.390f, 0.290f,
                Joint.R_SHOULDER, 0.590f, 0.290f,
                Joint.L_WRIST, 0.340f, 0.640f,
                Joint.R_WRIST, 0.660f, 0.640f,
                Joint.PELVIS, 0.530f, 0.840f,
                Joint.L_HIP, 0.470f, 0.870f,
                Joint.R_HIP, 0.590f, 0.870f,
            )
        },
        pose("hb_wave", "Vẫy tay", PoseCategory.HALF_BODY, 0.75f) {
            at(Joint.R_ELBOW, 0.680f, 0.300f)
            at(Joint.R_WRIST, 0.780f, 0.120f)
            at(Joint.L_WRIST, 0.320f, 0.640f)
        },
    )

    /** Nhãn COCO -> affordance có thể dùng cho dáng. */
    fun affordancesForLabel(label: String): Set<Affordance> = when (label.lowercase()) {
        "chair", "bench", "couch" -> setOf(Affordance.CHAIR)
        "dining table", "table" -> setOf(Affordance.TABLE)
        "potted plant", "tree" -> setOf(Affordance.TREE)
        "car", "truck", "bus", "motorcycle", "bicycle" -> setOf(Affordance.CAR)
        "wall", "door" -> setOf(Affordance.WALL)
        else -> emptySet()
    }
}
