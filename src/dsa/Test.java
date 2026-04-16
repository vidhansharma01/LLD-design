package dsa;

import java.util.Arrays;

class Test {

    // ── Rotate Array using Reverse Algorithm ──────────────────────────
    // Time: O(n)  |  Space: O(1)
    public static void rotate(int[] nums, int k) {
        k %= nums.length;           // handle k > n
        reverse(nums, 0, nums.length - 1);  // Step 1: reverse entire array
        reverse(nums, 0, k - 1);            // Step 2: reverse first k elements
        reverse(nums, k, nums.length - 1);  // Step 3: reverse remaining elements
    }

    private static void reverse(int[] nums, int l, int r) {
        while (l < r) {
            int temp = nums[l];
            nums[l++] = nums[r];
            nums[r--] = temp;
        }
    }

    public static void main(String[] args) {
        int[] nums = {1, 2, 3, 4, 5, 6, 7};
        int k = 3;

        System.out.println("Before rotation: " + Arrays.toString(nums));
        rotate(nums, k);
        System.out.println("After rotation (k=" + k + "): " + Arrays.toString(nums));
        // Expected: [5, 6, 7, 1, 2, 3, 4]

        // Edge case: k > n
        int[] nums2 = {1, 2, 3};
        rotate(nums2, 10); // 10 % 3 = 1
        System.out.println("Edge case (k=10, n=3): " + Arrays.toString(nums2));
        // Expected: [3, 1, 2]
    }
}

