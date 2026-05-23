# NumPy 7-Day Learning Plan for Beginners

> **Goal:** Go from zero to confident NumPy user in 7 days.
> **Prerequisites:** Basic Python (variables, loops, functions, lists).

---

## Day 1 — Introduction & Array Basics

### What is NumPy?
NumPy (Numerical Python) is the foundational library for numerical computing in Python.
It provides the **ndarray** (n-dimensional array) — a fast, memory-efficient container for homogeneous data.

**Why NumPy over plain Python lists?**
| Feature | Python List | NumPy Array |
|---|---|---|
| Speed | Slow (interpreted loop) | Fast (C under the hood) |
| Memory | More (objects) | Less (typed buffer) |
| Math ops | Manual loops | Vectorized (no loops) |
| Data type | Mixed | Homogeneous |

### Installation
```bash
pip install numpy
import numpy as np  # convention
```

### Creating Arrays

```python
# From a list
a = np.array([1, 2, 3, 4, 5])
print(a)          # [1 2 3 4 5]
print(type(a))    # <class 'numpy.ndarray'>

# 2D array (matrix)
b = np.array([[1, 2, 3],
              [4, 5, 6]])
print(b.shape)    # (2, 3)

# 3D array
c = np.array([[[1,2],[3,4]], [[5,6],[7,8]]])
print(c.shape)    # (2, 2, 2)
```

### Array Creation Functions

```python
np.zeros((3, 4))          # 3x4 matrix of zeros
np.ones((2, 3))           # 2x3 matrix of ones
np.full((2, 2), 7)        # 2x2 filled with 7
np.eye(4)                 # 4x4 identity matrix
np.arange(0, 10, 2)       # [0 2 4 6 8]
np.linspace(0, 1, 5)      # [0. 0.25 0.5 0.75 1.]
np.random.random((3, 3))  # 3x3 random floats [0,1)
np.random.randint(0, 10, (3,3))  # random ints
np.empty((2, 3))          # uninitialized (garbage values)
```

### Key Array Attributes

```python
a = np.array([[1,2,3],[4,5,6]])
a.shape     # (2, 3) — dimensions
a.ndim      # 2 — number of dimensions
a.size      # 6 — total elements
a.dtype     # dtype('int64') — data type
a.itemsize  # 8 — bytes per element
a.nbytes    # 48 — total bytes
```

### Data Types (dtype)

```python
np.array([1, 2, 3], dtype=np.int32)
np.array([1.5, 2.5], dtype=np.float64)
np.array([1, 2, 3], dtype=np.complex128)
np.array([True, False], dtype=np.bool_)

# Cast between types
a = np.array([1.7, 2.9])
a.astype(np.int32)  # [1 2] — truncates
```

### Practice Exercises — Day 1
1. Create a 5x5 matrix of zeros, then set the diagonal to 1 without using `np.eye`.
2. Create an array of 20 evenly spaced values between 0 and 100.
3. Create a 3x3 random integer array (values 1–50) and print its shape, size, and dtype.

---

## Day 2 — Indexing, Slicing & Reshaping

### Basic Indexing

```python
a = np.array([10, 20, 30, 40, 50])
a[0]    # 10
a[-1]   # 50
a[1:4]  # [20 30 40]
a[::2]  # [10 30 50] — every 2nd element
a[::-1] # [50 40 30 20 10] — reversed
```

### 2D Indexing

```python
m = np.array([[1,2,3],
              [4,5,6],
              [7,8,9]])

m[0, 1]    # 2 — row 0, col 1
m[1:, 1:]  # [[5,6],[8,9]] — submatrix
m[:, 0]    # [1,4,7] — entire first column
m[0, :]    # [1,2,3] — entire first row
m[::2, ::2]  # [[1,3],[7,9]] — stride both axes
```

### Fancy Indexing

```python
a = np.array([10, 20, 30, 40, 50])

# Integer array indexing
idx = [0, 2, 4]
a[idx]  # [10 30 50]

# Boolean indexing
mask = a > 25
a[mask]    # [30 40 50]
a[a % 20 == 0]  # [20 40]

# 2D fancy indexing
m = np.array([[1,2,3],[4,5,6],[7,8,9]])
rows = [0, 2]
cols = [1, 2]
m[rows, cols]  # [m[0,1], m[2,2]] = [2, 9]
```

### Reshaping

```python
a = np.arange(12)  # [0..11]

a.reshape(3, 4)    # 3 rows, 4 cols
a.reshape(2, 2, 3) # 3D
a.reshape(-1, 4)   # NumPy infers first dim = 3

# Flatten and ravel
m = np.array([[1,2],[3,4]])
m.flatten()  # [1 2 3 4] — returns copy
m.ravel()    # [1 2 3 4] — returns view (faster)

# Transpose
m.T          # [[1,3],[2,4]]
np.transpose(m)

# Add dimension
a = np.array([1,2,3])
a[:, np.newaxis]  # shape (3,1)
a[np.newaxis, :]  # shape (1,3)
np.expand_dims(a, axis=0)
```

### Views vs Copies

```python
a = np.array([1,2,3,4,5])

# Slicing returns a VIEW — modifying it modifies original!
b = a[1:4]
b[0] = 99
print(a)  # [1 99 3 4 5] — original changed!

# Use .copy() to avoid this
c = a[1:4].copy()
c[0] = 0
print(a)  # unchanged
```

### Practice Exercises — Day 2
1. Create a 4x4 matrix and extract the 2x2 submatrix from the bottom-right corner.
2. From `np.arange(1, 26).reshape(5,5)`, select all elements > 10 using boolean indexing.
3. Reshape a 1D array of 24 elements into shape (2, 3, 4).

---

## Day 3 — Math Operations & Broadcasting

### Element-wise Operations

```python
a = np.array([1, 2, 3, 4])
b = np.array([10, 20, 30, 40])

a + b   # [11 22 33 44]
a - b   # [-9 -18 -27 -36]
a * b   # [10 40 90 160]
a / b   # [0.1 0.1 0.1 0.1]
a ** 2  # [1 4 9 16]
a % 3   # [1 2 0 1]
```

### Universal Functions (ufuncs)

```python
a = np.array([1.0, 4.0, 9.0, 16.0])

np.sqrt(a)    # [1. 2. 3. 4.]
np.exp(a)     # e^a for each element
np.log(a)     # natural log
np.log2(a)
np.log10(a)
np.abs(np.array([-1, -2, 3]))  # [1 2 3]
np.sin(np.pi / 2)  # 1.0
np.cos(0)          # 1.0
np.floor(a)
np.ceil(a)
np.round(a, 2)
```

### Aggregation Functions

```python
a = np.array([[1,2,3],[4,5,6]])

np.sum(a)          # 21 — total sum
np.sum(a, axis=0)  # [5 7 9] — col sums
np.sum(a, axis=1)  # [6 15] — row sums

np.mean(a)         # 3.5
np.median(a)
np.std(a)          # standard deviation
np.var(a)          # variance
np.min(a)          # 1
np.max(a)          # 6
np.argmin(a)       # index of min in flattened array
np.argmax(a)       # index of max
np.cumsum(a)       # cumulative sum
np.cumprod(a)      # cumulative product
np.prod(a)         # product of all elements
```

### Broadcasting — The Key NumPy Concept

Broadcasting lets NumPy operate on arrays of **different shapes** without copying data.

**Broadcasting Rules:**
1. If arrays have different ndim, prepend 1s to the smaller shape.
2. Arrays with size 1 along a dimension are stretched to match.
3. If sizes don't match and neither is 1 → error.

```python
# Scalar broadcast
a = np.array([1, 2, 3])
a + 10          # [11 12 13]
a * 2           # [2 4 6]

# 1D + 2D
m = np.ones((3, 4))
v = np.array([1, 2, 3, 4])   # shape (4,)
m + v   # v broadcast across all 3 rows → (3,4)

# Column vector broadcast
col = np.array([[10], [20], [30]])  # shape (3,1)
m + col  # col broadcast across all 4 cols → (3,4)

# Classic outer product via broadcasting
row = np.array([1, 2, 3])          # (3,)
col = np.array([[1],[2],[3],[4]])   # (4,1)
row * col  # (4,3) — multiplication table
```

### Comparison Operators

```python
a = np.array([1, 5, 3, 8, 2])

a > 3        # [F T F T F]
a == 5       # [F T F F F]
a != 3       # [T T F T T]

np.any(a > 7)   # True
np.all(a > 0)   # True
np.where(a > 3, a, 0)  # [0 5 0 8 0] — conditional select
```

### Practice Exercises — Day 3
1. Create a 5x5 matrix and normalize it (subtract mean, divide by std).
2. Use broadcasting to create a multiplication table (1–10).
3. Find all indices where values in an array are between 20 and 50.

---

## Day 4 — Linear Algebra & Statistics

### Matrix Operations

```python
A = np.array([[1,2],[3,4]])
B = np.array([[5,6],[7,8]])

# Matrix multiplication
A @ B          # preferred way
np.dot(A, B)   # same result
np.matmul(A, B)

# Element-wise vs matrix multiply
A * B   # element-wise: [[5,12],[21,32]]
A @ B   # matrix multiply: [[19,22],[43,50]]
```

### numpy.linalg

```python
A = np.array([[1,2],[3,4]], dtype=float)

np.linalg.det(A)        # determinant: -2.0
np.linalg.inv(A)        # inverse matrix
np.linalg.rank(A)       # matrix rank

# Eigenvalues & eigenvectors
vals, vecs = np.linalg.eig(A)

# Singular Value Decomposition
U, S, Vt = np.linalg.svd(A)

# Solve linear equations Ax = b
b = np.array([5, 11])
x = np.linalg.solve(A, b)   # x = [1, 2]

# Norms
np.linalg.norm(A)            # Frobenius norm
np.linalg.norm(A, ord=1)     # column-sum norm
```

### Statistics with NumPy

```python
data = np.random.normal(loc=0, scale=1, size=1000)

np.mean(data)
np.median(data)
np.std(data)
np.var(data)
np.percentile(data, [25, 50, 75])  # quartiles
np.corrcoef(data[:500], data[500:])  # correlation matrix
np.histogram(data, bins=10)          # frequency counts

# Along axes
m = np.random.rand(4, 5)
np.mean(m, axis=0)    # mean of each column
np.mean(m, axis=1)    # mean of each row
```

### Random Number Generation

```python
rng = np.random.default_rng(seed=42)  # modern API

rng.random((3,3))               # uniform [0,1)
rng.integers(0, 10, size=(3,3)) # random ints
rng.normal(0, 1, size=100)      # Gaussian
rng.choice([1,2,3,4,5], size=3) # sample
rng.shuffle(arr)                # in-place shuffle
rng.permutation(arr)            # returns shuffled copy
```

### Practice Exercises — Day 4
1. Solve the system: `2x + 3y = 8`, `x - y = 1` using `np.linalg.solve`.
2. Generate 500 random numbers from a normal distribution and compute mean, std, and percentiles.
3. Create two 3x3 matrices and verify that `(A @ B)^-1 == B^-1 @ A^-1`.

---

## Day 5 — Array Manipulation & Advanced Indexing

### Stacking & Splitting

```python
a = np.array([1,2,3])
b = np.array([4,5,6])

np.vstack([a, b])        # vertical stack → (2,3)
np.hstack([a, b])        # horizontal stack → (6,)
np.stack([a, b], axis=0) # new axis → (2,3)
np.stack([a, b], axis=1) # → (3,2)
np.concatenate([a, b])   # (6,)

# 2D stacking
m1 = np.ones((2,3))
m2 = np.zeros((2,3))
np.vstack([m1, m2])      # (4,3)
np.hstack([m1, m2])      # (2,6)

# Splitting
a = np.arange(12)
np.split(a, 3)           # 3 equal parts
np.split(a, [3, 7])      # split at indices 3 and 7
np.vsplit(m1, 2)
np.hsplit(m1, 3)
```

### Sorting

```python
a = np.array([3,1,4,1,5,9,2,6])

np.sort(a)           # returns sorted copy
a.sort()             # in-place sort
np.argsort(a)        # indices that would sort array
np.sort(a)[::-1]     # descending sort

# Sort 2D along axis
m = np.array([[3,1],[2,4]])
np.sort(m, axis=0)   # sort columns
np.sort(m, axis=1)   # sort rows

# Find unique values
np.unique(a)
np.unique(a, return_counts=True)
```

### Advanced Indexing Tricks

```python
# np.where — if-else vectorized
a = np.array([1,-2,3,-4,5])
np.where(a > 0, a, 0)     # [1 0 3 0 5]
np.where(a > 0, 'pos', 'neg')

# np.clip — clamp values
a.clip(0, 3)   # [1 0 3 0 3] — clamp to [0,3]

# np.select — multiple conditions
conditions = [a < 0, a == 0, a > 0]
choices    = [-1, 0, 1]
np.select(conditions, choices)   # sign function

# np.take — fancy index
a = np.array([10,20,30,40,50])
np.take(a, [0, 2, 4])   # [10 30 50]

# np.put — fancy assign
np.put(a, [1, 3], [99, 88])

# np.roll — circular shift
np.roll(a, 2)    # shift right by 2
np.roll(a, -1)   # shift left by 1
```

### Set Operations

```python
a = np.array([1,2,3,4,5])
b = np.array([3,4,5,6,7])

np.intersect1d(a, b)   # [3 4 5]
np.union1d(a, b)       # [1 2 3 4 5 6 7]
np.setdiff1d(a, b)     # [1 2] — in a not b
np.isin(a, b)          # [F F T T T]
```

### Practice Exercises — Day 5
1. Stack three 3x3 identity matrices vertically. Then split the result into three equal parts.
2. Sort a 4x4 random matrix row-wise and column-wise separately.
3. Replace all negative numbers in an array with 0 using `np.where`.

---

## Day 6 — Performance, I/O & Real-world Patterns

### Vectorization vs Loops

```python
import time
import numpy as np

n = 1_000_000

# Slow: Python loop
start = time.time()
result = [x**2 for x in range(n)]
print(f"Loop: {time.time()-start:.3f}s")

# Fast: NumPy vectorized
start = time.time()
a = np.arange(n)
result = a**2
print(f"NumPy: {time.time()-start:.3f}s")
# NumPy is typically 10–100x faster
```

### Memory Layout — C vs Fortran Order

```python
# C order (row-major): default in NumPy
a = np.array([[1,2,3],[4,5,6]], order='C')
# Fortran order (col-major): better for col ops
b = np.array([[1,2,3],[4,5,6]], order='F')

a.flags  # C_CONTIGUOUS, F_CONTIGUOUS flags
np.ascontiguousarray(b)  # convert to C order
```

### Saving & Loading Arrays

```python
a = np.array([1,2,3,4,5])

# Binary formats (fast, lossless)
np.save('array.npy', a)           # single array
b = np.load('array.npy')

np.savez('arrays.npz', x=a, y=a*2)   # multiple arrays
data = np.load('arrays.npz')
data['x'], data['y']

# Text formats (human-readable)
np.savetxt('data.csv', a, delimiter=',', fmt='%.4f')
b = np.loadtxt('data.csv', delimiter=',')

# Compressed
np.savez_compressed('compressed.npz', arr=a)
```

### Working with Real Data

```python
# Load CSV data
data = np.genfromtxt('sales.csv', delimiter=',',
                      skip_header=1, dtype=float)

# Structured arrays (records)
dt = np.dtype([('name', 'U10'), ('age', 'i4'), ('score', 'f4')])
records = np.array([('Alice', 25, 9.5),
                    ('Bob',   30, 8.2)], dtype=dt)
records['name']    # ['Alice' 'Bob']
records['score']   # [9.5 8.2]
```

### Practical Patterns

```python
# Moving average
def moving_avg(a, window):
    return np.convolve(a, np.ones(window)/window, mode='valid')

# Normalize data (min-max)
def normalize(a):
    return (a - a.min()) / (a.max() - a.min())

# Z-score standardization
def standardize(a):
    return (a - a.mean()) / a.std()

# One-hot encoding
def one_hot(labels, num_classes):
    result = np.zeros((len(labels), num_classes))
    result[np.arange(len(labels)), labels] = 1
    return result

# Euclidean distance matrix
def dist_matrix(X):
    # X: (n, d) — n points in d dimensions
    diff = X[:, np.newaxis, :] - X[np.newaxis, :, :]
    return np.sqrt((diff**2).sum(axis=-1))
```

### Practice Exercises — Day 6
1. Time the difference between a Python loop and NumPy for computing `sin(x)` for 1 million values.
2. Save and reload a 100x100 random matrix using `.npy` and `.csv` formats. Compare file sizes.
3. Implement min-max normalization and apply it to a random dataset.

---

## Day 7 — Projects & Putting It All Together

### Project 1: Image Manipulation with NumPy

```python
import numpy as np

# Images are just 3D arrays: (height, width, channels)
# Create a simple gradient image
height, width = 100, 100
img = np.zeros((height, width, 3), dtype=np.uint8)

# Red gradient left-to-right
img[:, :, 0] = np.linspace(0, 255, width)

# Green gradient top-to-bottom
img[:, :, 1] = np.linspace(0, 255, height)[:, np.newaxis]

# Grayscale conversion
def to_grayscale(img):
    # Standard luminance weights
    weights = np.array([0.2989, 0.5870, 0.1140])
    return (img @ weights).astype(np.uint8)

# Flip image
flipped_h = img[:, ::-1, :]  # horizontal flip
flipped_v = img[::-1, :, :]  # vertical flip

# Crop
crop = img[10:50, 20:80, :]

# Brighten (clip to 255)
bright = np.clip(img.astype(int) + 50, 0, 255).astype(np.uint8)
```

### Project 2: Statistics on Student Data

```python
import numpy as np

np.random.seed(42)
# 100 students, 5 subjects
scores = np.random.randint(40, 100, size=(100, 5))
subjects = ['Math', 'Science', 'English', 'History', 'Art']

# Summary statistics per subject
print("Subject Averages:", np.mean(scores, axis=0))
print("Highest Scores:", np.max(scores, axis=0))
print("Pass Rate (>=60):", np.mean(scores >= 60, axis=0) * 100)

# Overall student performance
student_avg = np.mean(scores, axis=1)
top_students = np.where(student_avg >= 85)[0]
print(f"Top students (avg>=85): {len(top_students)}")

# Grade assignment
grades = np.select(
    [student_avg >= 90, student_avg >= 75,
     student_avg >= 60, student_avg < 60],
    ['A', 'B', 'C', 'F']
)

# Rank students
ranks = np.argsort(student_avg)[::-1] + 1
print("Top 5 student ranks:", ranks[:5])
```

### Project 3: Simple Linear Regression from Scratch

```python
import numpy as np

# Generate synthetic data: y = 2x + 3 + noise
np.random.seed(0)
X = np.linspace(0, 10, 100)
y = 2 * X + 3 + np.random.normal(0, 1, 100)

# Add bias column (intercept)
X_b = np.column_stack([np.ones(100), X])  # (100, 2)

# Closed-form solution: w = (X^T X)^{-1} X^T y
w = np.linalg.inv(X_b.T @ X_b) @ X_b.T @ y
print(f"Intercept: {w[0]:.2f}, Slope: {w[1]:.2f}")
# Should be close to intercept=3, slope=2

# Predictions
y_pred = X_b @ w
residuals = y - y_pred
mse = np.mean(residuals**2)
r2 = 1 - np.sum(residuals**2) / np.sum((y - y.mean())**2)
print(f"MSE: {mse:.4f}, R²: {r2:.4f}")
```

### Project 4: Monte Carlo Pi Estimation

```python
import numpy as np

def estimate_pi(n_samples=1_000_000):
    rng = np.random.default_rng(seed=42)
    # Random points in unit square
    points = rng.random((n_samples, 2))
    # Points inside unit circle
    inside = np.sum(points[:, 0]**2 + points[:, 1]**2 <= 1)
    return 4 * inside / n_samples

print(f"π ≈ {estimate_pi():6f}")   # ~3.141592
```

### Day 7 Final Challenge
Build a **Student Grade Analyzer**:
1. Generate random scores for 200 students across 6 subjects.
2. Compute per-student average, per-subject average.
3. Assign letter grades using `np.select`.
4. Find the top 10 students and bottom 10 students.
5. Compute the correlation matrix between subjects.
6. Save the results to a `.npz` file and reload them.

---

## Quick Reference Cheat Sheet

### Array Creation
```python
np.array([1,2,3])        np.zeros((m,n))
np.ones((m,n))           np.eye(n)
np.arange(start,stop,step)  np.linspace(a,b,n)
np.random.rand(m,n)      np.random.randn(m,n)
```

### Indexing
```python
a[i]        a[i,j]       a[i:j]
a[::k]      a[mask]      a[[i,j,k]]
```

### Math
```python
np.sum(a, axis=)    np.mean()    np.std()
np.min() / np.max() np.dot(A,B)  A @ B
np.sqrt() np.exp()  np.log()
```

### Shape
```python
a.reshape(m,n)    a.T           np.vstack()
np.hstack()       np.concatenate() a.flatten()
```

### Useful
```python
np.where(cond,x,y)   np.clip(a,lo,hi)
np.sort(a)           np.argsort(a)
np.unique(a)         np.isin(a,b)
np.save/load         np.savetxt/loadtxt
```

---

## Recommended Next Steps After Day 7

| Library | Builds On | Purpose |
|---|---|---|
| **Pandas** | NumPy arrays | Tabular data, DataFrames |
| **Matplotlib** | NumPy arrays | Plotting and visualization |
| **Scikit-learn** | NumPy arrays | Machine learning |
| **SciPy** | NumPy arrays | Scientific computing |
| **TensorFlow/PyTorch** | NumPy-like API | Deep learning |

> **Tip:** Every major Python data science library accepts or returns NumPy arrays. Mastering NumPy is the single best investment for any data/ML engineer.
