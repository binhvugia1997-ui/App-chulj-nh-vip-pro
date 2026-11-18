package com.gia.aicomposer.pose

/**
 * Thư viện dáng viết tay: mỗi dáng là tập keypoint tỉ lệ 0..1 trong khung riêng
 * và tỉ lệ aspect thật của khung đó.
 *
 * affordance bắt buộc (ghế / bàn / cây / xe / tường) là điều kiện để dáng được chọn,
 * affordance khuyến khích (mặt đất trống) chỉ cộng điểm.
 */
enum class PoseCategory { FULL_BODY, HALF_BODY }

enum class Affordance { CHAIR, TABLE, TREE, CAR, WALL, OPEN_GROUND }

enum class Joint {
    HEAD, NECK,
    L_SHOULDER, R_SHOULDER,
    L_ELBOW, R_ELBOW,
    L_WRIST, R_WRIST,
    PELVIS, L_HIP, R_HIP,
    L_KNEE, R_KNEE,
    L_ANKLE, R_ANKLE,
}

data class PosePoint(val x: Float, val y: Float)

data class PoseTemplate(
    val id: String,
    val displayName: String,
    val category: PoseCategory,
    /** aspect = width / height của khung riêng chứa dáng. */
    val aspect: Float,
    val requiredAffordances: Set<Affordance>,
    val encouragedAffordances: Set<Affordance>,
    val joints: Map<Joint, PosePoint>,
) {
    /** Các xương vẽ được từ tập keypoint hiện có (dáng nửa người không có chân). */
    val bones: List<Pair<Joint, Joint>> =
        BONE_DEFINITIONS.filter { joints.containsKey(it.first) && joints.containsKey(it.second) }

    companion object {
        val BONE_DEFINITIONS: List<Pair<Joint, Joint>> = listOf(
            Joint.HEAD to Joint.NECK,
            Joint.NECK to Joint.L_SHOULDER,
            Joint.NECK to Joint.R_SHOULDER,
            Joint.L_SHOULDER to Joint.R_SHOULDER,
            Joint.L_SHOULDER to Joint.PELVIS,
            Joint.R_SHOULDER to Joint.PELVIS,
            Joint.L_SHOULDER to Joint.L_ELBOW,
            Joint.L_ELBOW to Joint.L_WRIST,
            Joint.R_SHOULDER to Joint.R_ELBOW,
            Joint.R_ELBOW to Joint.R_WRIST,
            Joint.PELVIS to Joint.L_HIP,
            Joint.PELVIS to Joint.R_HIP,
            Joint.L_HIP to Joint.R_HIP,
            Joint.L_HIP to Joint.L_KNEE,
            Joint.L_KNEE to Joint.L_ANKLE,
            Joint.R_HIP to Joint.R_KNEE,
            Joint.R_KNEE to Joint.R_ANKLE,
        )
    }
}
