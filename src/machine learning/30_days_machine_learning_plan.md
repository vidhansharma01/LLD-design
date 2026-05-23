# 🚀 30-Day Machine Learning Mastery Plan

> **Audience:** Absolute Beginners  
> **Goal:** Go from zero to confidently understanding and building Machine Learning models  
> **Daily Commitment:** ~2–3 hours of study + practice  
> **Tools Used:** Python, NumPy, Pandas, Matplotlib, Scikit-learn, TensorFlow/Keras

---

## 📋 Table of Contents

| Week | Days | Focus Area |
|------|------|------------|
| **Week 1** | Days 1–7 | Python Foundations & Math Essentials |
| **Week 2** | Days 8–14 | Core ML Algorithms (Supervised Learning) |
| **Week 3** | Days 15–21 | Advanced ML Concepts & Unsupervised Learning |
| **Week 4** | Days 22–28 | Deep Learning & Neural Networks |
| **Finale** | Days 29–30 | Capstone Project & Next Steps |

---

---

# 🗓️ WEEK 1: Python Foundations & Math Essentials

---

## Day 1: Introduction to Machine Learning — The Big Picture

### What is Machine Learning?

Machine Learning (ML) is a subset of **Artificial Intelligence (AI)** that gives computers the ability to **learn from data** without being explicitly programmed for every scenario.

Think of it this way:

- **Traditional Programming:** You write rules → the computer follows those rules.
  - *Example:* "If temperature > 30°C, turn on AC."
- **Machine Learning:** You give data + expected outcomes → the computer discovers the rules.
  - *Example:* You give thousands of temperature readings and whether the AC was turned on → the computer learns the pattern and predicts when to turn it on.

### Why Does Machine Learning Matter?

ML is behind many technologies you use daily:

| Application | How ML is Used |
|-------------|---------------|
| Email Spam Filtering | Learns which emails are spam based on past examples |
| Netflix Recommendations | Learns your viewing preferences to suggest movies |
| Voice Assistants (Siri, Alexa) | Learns to understand spoken language |
| Self-Driving Cars | Learns to recognize objects, lanes, pedestrians |
| Medical Diagnosis | Learns to identify diseases from medical images |

### Types of Machine Learning

There are **three main types** of Machine Learning:

#### 1. Supervised Learning
- **What it is:** The algorithm learns from **labeled data** — meaning you provide both the input and the correct output.
- **Analogy:** A teacher gives you questions (inputs) along with the answer key (outputs). You study both to learn the pattern so you can answer new questions on your own.
- **Examples:**
  - Predicting house prices (input: size, location → output: price)
  - Classifying emails as spam or not spam
- **Two sub-types:**
  - **Regression:** Predicting a continuous number (e.g., price = $350,000)
  - **Classification:** Predicting a category (e.g., spam or not-spam)

#### 2. Unsupervised Learning
- **What it is:** The algorithm learns from **unlabeled data** — you only provide inputs, and the algorithm must find hidden patterns on its own.
- **Analogy:** You are given a box of assorted candies with no labels. You group them by color, shape, and size on your own.
- **Examples:**
  - Grouping customers by purchasing behavior (clustering)
  - Reducing the number of features in a dataset (dimensionality reduction)

#### 3. Reinforcement Learning
- **What it is:** The algorithm learns by **trial and error**, receiving rewards for good actions and penalties for bad ones.
- **Analogy:** Training a dog — you give treats for sitting on command and say "no" for jumping on people. Over time, the dog learns the desired behavior.
- **Examples:**
  - Training a robot to walk
  - Teaching an AI to play chess or video games

### The Machine Learning Workflow

Every ML project follows these steps:

```
1. Define the Problem
       ↓
2. Collect & Prepare Data
       ↓
3. Choose a Model
       ↓
4. Train the Model
       ↓
5. Evaluate the Model
       ↓
6. Tune & Improve
       ↓
7. Deploy & Monitor
```

### Key Terminology

| Term | Definition |
|------|-----------|
| **Feature** | An input variable (e.g., house size, number of bedrooms) |
| **Label / Target** | The output variable you want to predict (e.g., house price) |
| **Training Data** | The data used to teach the model |
| **Test Data** | The data used to evaluate how well the model learned |
| **Model** | The mathematical function that maps inputs to outputs |
| **Prediction** | The output the model gives for new, unseen data |
| **Overfitting** | The model memorizes training data but fails on new data |
| **Underfitting** | The model is too simple to capture the pattern in data |

### 🎯 Day 1 Practice

- Read and re-read the types of ML until you can explain them in your own words.
- Write down 5 real-world applications and identify which type of ML each one uses.
- Install Python (if not already installed) and set up a coding environment (Jupyter Notebook, VS Code, or Google Colab).

---

## Day 2: Python Essentials for Machine Learning

### Why Python for ML?

Python is the **#1 language for Machine Learning** because:
- It has a simple, readable syntax (great for beginners).
- It has powerful libraries: NumPy, Pandas, Scikit-learn, TensorFlow, PyTorch.
- It has a massive community and tons of free resources.

### Core Python Concepts You Need

#### 1. Variables and Data Types

Variables store data. Python automatically detects the data type.

```python
# Integer (whole number)
age = 25

# Float (decimal number)
height = 5.9

# String (text)
name = "Alice"

# Boolean (True or False)
is_student = True

# Check the type of a variable
print(type(age))      # <class 'int'>
print(type(height))   # <class 'float'>
print(type(name))     # <class 'str'>
```

#### 2. Lists (Ordered, Mutable Collections)

Lists are the most common data structure in Python. Think of them as containers that hold multiple items.

```python
# Creating a list
scores = [85, 92, 78, 95, 88]

# Accessing elements (0-indexed: first element is at index 0)
print(scores[0])    # 85 (first element)
print(scores[-1])   # 88 (last element)

# Slicing (getting a sub-list)
print(scores[1:3])  # [92, 78] (index 1 up to but NOT including 3)

# Modifying
scores.append(91)       # Add to end: [85, 92, 78, 95, 88, 91]
scores[0] = 90          # Change first element: [90, 92, 78, 95, 88, 91]

# List length
print(len(scores))      # 6

# List comprehension (powerful shorthand)
doubled = [x * 2 for x in scores]  # [170, 184, 156, 190, 176, 182]
```

#### 3. Dictionaries (Key-Value Pairs)

Dictionaries store data as key-value pairs — like a real dictionary where you look up a word (key) to find its meaning (value).

```python
# Creating a dictionary
student = {
    "name": "Alice",
    "age": 25,
    "grades": [85, 92, 78]
}

# Accessing values by key
print(student["name"])      # Alice
print(student["grades"])    # [85, 92, 78]

# Adding a new key-value pair
student["major"] = "Computer Science"

# Iterating over a dictionary
for key, value in student.items():
    print(f"{key}: {value}")
```

#### 4. Conditional Statements (if/elif/else)

```python
score = 85

if score >= 90:
    grade = "A"
elif score >= 80:
    grade = "B"
elif score >= 70:
    grade = "C"
else:
    grade = "F"

print(f"Your grade is: {grade}")  # Your grade is: B
```

#### 5. Loops

```python
# For loop — iterate over a sequence
fruits = ["apple", "banana", "cherry"]
for fruit in fruits:
    print(fruit)

# For loop with range
for i in range(5):        # 0, 1, 2, 3, 4
    print(i)

# While loop — repeat until condition is false
count = 0
while count < 3:
    print(f"Count is {count}")
    count += 1
```

#### 6. Functions

Functions are reusable blocks of code. In ML, you will write many functions to process data.

```python
# Defining a function
def calculate_mean(numbers):
    """Calculate the average of a list of numbers."""
    total = sum(numbers)
    count = len(numbers)
    return total / count

# Using the function
scores = [85, 92, 78, 95, 88]
average = calculate_mean(scores)
print(f"Average score: {average}")  # Average score: 87.6
```

#### 7. Lambda Functions (One-line Functions)

```python
# Regular function
def square(x):
    return x ** 2

# Same thing as a lambda
square = lambda x: x ** 2

print(square(5))  # 25
```

### 🎯 Day 2 Practice

- Write a function that takes a list of numbers and returns the maximum, minimum, and mean.
- Create a dictionary to store information about 3 different ML models (name, type, accuracy).
- Practice list comprehensions: create a list of squares of all even numbers from 1 to 20.

---

## Day 3: NumPy — The Foundation of Numerical Computing

### What is NumPy?

**NumPy** (Numerical Python) is the fundamental library for numerical computing in Python. It provides:
- **ndarray** — a powerful N-dimensional array object
- Mathematical functions that operate on entire arrays at once
- Tools for linear algebra, random numbers, and more

### Why is NumPy Important for ML?

Machine Learning is fundamentally about **mathematical operations on large datasets**. NumPy makes these operations:
- **Fast** — written in C under the hood, so it is 10–100x faster than pure Python lists.
- **Memory-efficient** — uses less memory than Python lists.
- **Convenient** — provides built-in functions for common operations.

### NumPy Arrays vs Python Lists

```python
import numpy as np

# Python list
python_list = [1, 2, 3, 4, 5]

# NumPy array
numpy_array = np.array([1, 2, 3, 4, 5])

# The key difference: element-wise operations
# With Python lists, you need a loop:
doubled_list = [x * 2 for x in python_list]  # [2, 4, 6, 8, 10]

# With NumPy, it's one operation (vectorization):
doubled_array = numpy_array * 2               # array([2, 4, 6, 8, 10])
```

### Creating Arrays

```python
import numpy as np

# From a list
a = np.array([1, 2, 3, 4, 5])
print(a)           # [1 2 3 4 5]
print(a.shape)     # (5,) → 1D array with 5 elements

# 2D array (matrix) — this is how data is typically stored in ML
b = np.array([
    [1, 2, 3],
    [4, 5, 6],
    [7, 8, 9]
])
print(b.shape)     # (3, 3) → 3 rows, 3 columns

# Special arrays
zeros = np.zeros((3, 4))       # 3×4 array of zeros
ones = np.ones((2, 3))         # 2×3 array of ones
identity = np.eye(3)           # 3×3 identity matrix
random = np.random.rand(3, 3)  # 3×3 array of random numbers [0, 1)
range_arr = np.arange(0, 10, 2)  # [0, 2, 4, 6, 8]
linspace = np.linspace(0, 1, 5)  # [0.0, 0.25, 0.5, 0.75, 1.0]
```

### Array Operations

```python
a = np.array([1, 2, 3, 4, 5])
b = np.array([10, 20, 30, 40, 50])

# Element-wise arithmetic
print(a + b)    # [11, 22, 33, 44, 55]
print(a * b)    # [10, 40, 90, 160, 250]
print(a ** 2)   # [1, 4, 9, 16, 25]

# Statistical functions
print(np.mean(a))     # 3.0 (average)
print(np.median(a))   # 3.0 (middle value)
print(np.std(a))      # 1.414... (standard deviation)
print(np.sum(a))      # 15
print(np.min(a))      # 1
print(np.max(a))      # 5
```

### Indexing and Slicing

```python
matrix = np.array([
    [1, 2, 3],
    [4, 5, 6],
    [7, 8, 9]
])

# Access single element: matrix[row, column]
print(matrix[0, 0])    # 1 (top-left)
print(matrix[2, 2])    # 9 (bottom-right)

# Access entire row
print(matrix[1])        # [4, 5, 6]

# Access entire column
print(matrix[:, 1])     # [2, 5, 8]

# Slicing: matrix[row_start:row_end, col_start:col_end]
print(matrix[0:2, 1:3]) # [[2, 3], [5, 6]]

# Boolean indexing (very useful in ML!)
a = np.array([1, 2, 3, 4, 5])
print(a[a > 3])         # [4, 5] — only elements greater than 3
```

### Reshaping Arrays

```python
a = np.array([1, 2, 3, 4, 5, 6])

# Reshape from 1D to 2D
b = a.reshape(2, 3)    # 2 rows, 3 columns
# [[1, 2, 3],
#  [4, 5, 6]]

c = a.reshape(3, 2)    # 3 rows, 2 columns
# [[1, 2],
#  [3, 4],
#  [5, 6]]

# Use -1 to let NumPy figure out the dimension
d = a.reshape(-1, 1)   # Column vector (6 rows, 1 column)
```

### Broadcasting

Broadcasting is how NumPy handles operations between arrays of different shapes.

```python
# Scalar broadcast: add 10 to every element
a = np.array([1, 2, 3])
print(a + 10)           # [11, 12, 13]

# Matrix + vector broadcast
matrix = np.array([
    [1, 2, 3],
    [4, 5, 6]
])
row = np.array([10, 20, 30])
print(matrix + row)
# [[11, 22, 33],
#  [14, 25, 36]]
# The row is "broadcast" to each row of the matrix
```

### 🎯 Day 3 Practice

- Create a 5×5 random matrix and find its mean, max value, and the index of its max value.
- Create two 3×3 matrices and perform element-wise addition, multiplication, and matrix multiplication (`np.dot`).
- Use boolean indexing to filter all values greater than 0.5 from a random array.

---

## Day 4: Pandas — Data Manipulation & Analysis

### What is Pandas?

**Pandas** is Python's most popular data manipulation library. It provides two key data structures:

| Structure | Description | Analogy |
|-----------|-------------|---------|
| **Series** | A 1D labeled array | A single column of a spreadsheet |
| **DataFrame** | A 2D labeled table | An entire spreadsheet/table |

In Machine Learning, your data will almost always be in a **DataFrame**.

### Creating DataFrames

```python
import pandas as pd

# From a dictionary
data = {
    "Name": ["Alice", "Bob", "Charlie", "Diana"],
    "Age": [25, 30, 35, 28],
    "Salary": [50000, 60000, 70000, 55000],
    "Department": ["HR", "Engineering", "Engineering", "Marketing"]
}

df = pd.DataFrame(data)
print(df)
#       Name  Age  Salary    Department
# 0    Alice   25   50000            HR
# 1      Bob   30   60000   Engineering
# 2  Charlie   35   70000   Engineering
# 3    Diana   28   55000     Marketing
```

### Exploring Data (First Steps with Any Dataset)

```python
# Shape: (rows, columns)
print(df.shape)          # (4, 4)

# First/last N rows
print(df.head(2))        # First 2 rows
print(df.tail(2))        # Last 2 rows

# Column names and data types
print(df.columns)        # Index(['Name', 'Age', 'Salary', 'Department'])
print(df.dtypes)         # Data type of each column

# Summary statistics
print(df.describe())     # Count, mean, std, min, 25%, 50%, 75%, max

# Info about the DataFrame
print(df.info())         # Column names, non-null counts, data types
```

### Selecting Data

```python
# Select a single column (returns a Series)
print(df["Name"])

# Select multiple columns (returns a DataFrame)
print(df[["Name", "Salary"]])

# Select rows by index number (iloc = integer location)
print(df.iloc[0])        # First row
print(df.iloc[0:2])      # First 2 rows

# Select rows by label (loc = label location)
print(df.loc[0, "Name"]) # "Alice"

# Conditional selection (VERY important for ML data prep)
high_earners = df[df["Salary"] > 55000]
print(high_earners)
#       Name  Age  Salary    Department
# 1      Bob   30   60000   Engineering
# 2  Charlie   35   70000   Engineering
```

### Handling Missing Data

Missing data is one of the **biggest challenges** in real-world ML. Pandas uses `NaN` (Not a Number) for missing values.

```python
import numpy as np

# Create data with missing values
data = {
    "Name": ["Alice", "Bob", None, "Diana"],
    "Age": [25, np.nan, 35, 28],
    "Salary": [50000, 60000, np.nan, 55000]
}
df = pd.DataFrame(data)

# Check for missing values
print(df.isnull())          # True/False for each cell
print(df.isnull().sum())    # Count of missing values per column

# Option 1: Drop rows with any missing value
df_dropped = df.dropna()

# Option 2: Fill missing values with a specific value
df_filled = df.fillna({"Age": df["Age"].mean(), "Name": "Unknown"})

# Option 3: Forward fill (use previous row's value)
df_ffill = df.fillna(method="ffill")
```

### Grouping and Aggregation

```python
# Group by department and calculate mean salary
grouped = df.groupby("Department")["Salary"].mean()
print(grouped)
# Department
# Engineering    65000.0
# HR             50000.0
# Marketing      55000.0

# Multiple aggregations
agg = df.groupby("Department").agg({
    "Salary": ["mean", "max", "min"],
    "Age": "mean"
})
print(agg)
```

### Adding and Modifying Columns

```python
# Add a new column
df["Bonus"] = df["Salary"] * 0.10

# Apply a function to a column
df["Age_Group"] = df["Age"].apply(lambda x: "Senior" if x >= 30 else "Junior")

# Rename columns
df = df.rename(columns={"Salary": "Annual_Salary"})
```

### Reading and Writing Data

```python
# Read from CSV file (most common in ML)
df = pd.read_csv("dataset.csv")

# Read from Excel
df = pd.read_excel("dataset.xlsx")

# Write to CSV
df.to_csv("output.csv", index=False)
```

### 🎯 Day 4 Practice

- Download any CSV dataset (e.g., from Kaggle) and load it using Pandas.
- Explore it: check shape, data types, missing values, and summary statistics.
- Filter rows based on conditions, group by a categorical column, and compute aggregations.

---

## Day 5: Data Visualization with Matplotlib & Seaborn

### Why Visualization?

> "A picture is worth a thousand data points."

Visualization helps you:
- **Understand** the distribution and relationships in your data
- **Identify** outliers, patterns, and anomalies
- **Communicate** results to others
- **Debug** your ML models

### Matplotlib — The Foundation

Matplotlib is Python's core plotting library. Everything else builds on top of it.

```python
import matplotlib.pyplot as plt
import numpy as np

# --- Line Plot ---
x = np.linspace(0, 10, 100)
y = np.sin(x)

plt.figure(figsize=(10, 6))
plt.plot(x, y, color='blue', linewidth=2, label='sin(x)')
plt.plot(x, np.cos(x), color='red', linewidth=2, label='cos(x)')
plt.title("Sine and Cosine Waves", fontsize=16)
plt.xlabel("x", fontsize=12)
plt.ylabel("y", fontsize=12)
plt.legend()
plt.grid(True, alpha=0.3)
plt.show()
```

```python
# --- Scatter Plot (for seeing relationships between two variables) ---
np.random.seed(42)
x = np.random.rand(100) * 10
y = 2 * x + 1 + np.random.randn(100) * 2  # y = 2x + 1 + noise

plt.figure(figsize=(10, 6))
plt.scatter(x, y, color='purple', alpha=0.6, edgecolors='black')
plt.title("Scatter Plot: X vs Y", fontsize=16)
plt.xlabel("X", fontsize=12)
plt.ylabel("Y", fontsize=12)
plt.show()
```

```python
# --- Histogram (for seeing the distribution of a single variable) ---
data = np.random.randn(1000)  # 1000 random numbers from normal distribution

plt.figure(figsize=(10, 6))
plt.hist(data, bins=30, color='teal', edgecolor='black', alpha=0.7)
plt.title("Histogram of Random Data", fontsize=16)
plt.xlabel("Value", fontsize=12)
plt.ylabel("Frequency", fontsize=12)
plt.show()
```

```python
# --- Bar Chart ---
categories = ['A', 'B', 'C', 'D']
values = [23, 45, 12, 67]

plt.figure(figsize=(8, 5))
plt.bar(categories, values, color=['#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4'])
plt.title("Bar Chart", fontsize=16)
plt.ylabel("Value", fontsize=12)
plt.show()
```

### Seaborn — Beautiful Statistical Plots

Seaborn is built on top of Matplotlib and makes beautiful, informative plots with less code.

```python
import seaborn as sns
import pandas as pd

# Load a built-in dataset
tips = sns.load_dataset("tips")

# --- Box Plot (shows distribution + outliers) ---
plt.figure(figsize=(10, 6))
sns.boxplot(x="day", y="total_bill", data=tips, palette="Set2")
plt.title("Total Bill by Day", fontsize=16)
plt.show()

# --- Violin Plot (box plot + density) ---
plt.figure(figsize=(10, 6))
sns.violinplot(x="day", y="total_bill", data=tips, palette="muted")
plt.title("Total Bill Distribution by Day", fontsize=16)
plt.show()

# --- Heatmap (correlation matrix — ESSENTIAL for ML) ---
plt.figure(figsize=(8, 6))
correlation = tips[["total_bill", "tip", "size"]].corr()
sns.heatmap(correlation, annot=True, cmap="coolwarm", center=0)
plt.title("Correlation Heatmap", fontsize=16)
plt.show()

# --- Pair Plot (scatter plots of ALL variable pairs) ---
sns.pairplot(tips, hue="sex", palette="husl")
plt.show()
```

### Plots Most Useful for ML

| Plot | When to Use | What It Shows |
|------|------------|--------------|
| **Histogram** | Before training | Distribution of each feature |
| **Scatter Plot** | Feature analysis | Relationship between two features |
| **Heatmap** | Feature selection | Correlation between all features |
| **Box Plot** | Data cleaning | Outliers in each feature |
| **Pair Plot** | Exploratory analysis | All pairwise relationships at once |
| **Confusion Matrix** | After classification | Model's correct vs incorrect predictions |
| **ROC Curve** | Model evaluation | Trade-off between sensitivity and specificity |

### 🎯 Day 5 Practice

- Load a dataset and create: histogram, scatter plot, box plot, and correlation heatmap.
- Practice customizing plots: colors, labels, titles, legends, and figure sizes.
- Create a pair plot and identify which features seem correlated.

---

## Day 6: Mathematics for Machine Learning — Linear Algebra & Statistics

### Why Math?

Machine Learning IS applied mathematics. Understanding the math gives you:
- **Intuition** about why algorithms work
- **Debugging ability** when things go wrong
- **Power to customize** algorithms for your specific needs

You don't need to be a math genius — you need to understand the **concepts**.

### Part 1: Linear Algebra

#### Scalars, Vectors, and Matrices

```
Scalar:   A single number.                     Example: 5
Vector:   A 1D array of numbers.               Example: [1, 2, 3]
Matrix:   A 2D array of numbers (rows × cols). Example: [[1,2],[3,4]]
Tensor:   An N-dimensional array.              Example: A 3D cube of numbers
```

**In ML:**
- A **scalar** is a single feature value (e.g., age = 25)
- A **vector** is a single data point with multiple features (e.g., [age, height, weight] = [25, 5.9, 70])
- A **matrix** is your entire dataset (rows = samples, columns = features)
- A **tensor** is used in deep learning (e.g., an image is a 3D tensor: height × width × color channels)

#### Dot Product

The dot product is the most important operation in ML. It multiplies corresponding elements and sums them.

```python
import numpy as np

a = np.array([1, 2, 3])
b = np.array([4, 5, 6])

# Dot product: (1×4) + (2×5) + (3×6) = 4 + 10 + 18 = 32
dot = np.dot(a, b)
print(dot)  # 32
```

**Why it matters:** In ML, making a prediction is essentially computing a dot product:
```
prediction = (weight₁ × feature₁) + (weight₂ × feature₂) + ... + bias
```

#### Matrix Multiplication

```python
A = np.array([[1, 2],
              [3, 4]])

B = np.array([[5, 6],
              [7, 8]])

# Matrix multiplication
C = np.dot(A, B)  # or A @ B
# [[1×5+2×7, 1×6+2×8],   = [[19, 22],
#  [3×5+4×7, 3×6+4×8]]      [43, 50]]
```

**Why it matters:** Neural networks are essentially chains of matrix multiplications.

#### Transpose

Flipping a matrix over its diagonal (rows become columns, columns become rows).

```python
A = np.array([[1, 2, 3],
              [4, 5, 6]])
# Shape: (2, 3)

A_T = A.T
# [[1, 4],
#  [2, 5],
#  [3, 6]]
# Shape: (3, 2)
```

### Part 2: Statistics

#### Mean, Median, Mode

```python
import numpy as np
from scipy import stats

data = [10, 20, 20, 30, 40, 50, 100]

mean = np.mean(data)        # 38.57 — average (sensitive to outliers)
median = np.median(data)    # 30.0  — middle value (robust to outliers)
mode = stats.mode(data)     # 20    — most frequent value
```

**When to use which:**
- **Mean** — general purpose, but affected by outliers
- **Median** — when data has outliers (e.g., income data)
- **Mode** — for categorical data (e.g., most common color)

#### Variance and Standard Deviation

These measure **how spread out** your data is.

```python
data = np.array([2, 4, 4, 4, 5, 5, 7, 9])

variance = np.var(data)       # 4.0 — average squared distance from mean
std_dev = np.std(data)        # 2.0 — square root of variance

# Small std_dev → data points are close to the mean
# Large std_dev → data points are spread out
```

**Why it matters in ML:**
- Features with very different scales (e.g., age: 0–100, salary: 0–1,000,000) can bias the model.
- We use **standardization** to give all features a mean of 0 and std of 1.

#### Correlation

Correlation measures the **linear relationship** between two variables. It ranges from -1 to +1.

```
+1 → Perfect positive correlation (as X increases, Y increases)
 0 → No correlation
-1 → Perfect negative correlation (as X increases, Y decreases)
```

```python
import numpy as np

# Positive correlation
x = np.array([1, 2, 3, 4, 5])
y = np.array([2, 4, 5, 4, 5])

correlation = np.corrcoef(x, y)[0, 1]
print(f"Correlation: {correlation:.2f}")  # ~0.83
```

#### Normal Distribution (Gaussian Distribution)

The **bell curve** — many real-world phenomena follow this distribution.

```
Properties:
- Symmetric around the mean
- Mean = Median = Mode
- 68% of data falls within 1 standard deviation of the mean
- 95% within 2 standard deviations
- 99.7% within 3 standard deviations
```

**Why it matters:** Many ML algorithms assume data is normally distributed. Understanding this helps you preprocess data correctly.

### Part 3: Probability Basics

#### Key Concepts

```
P(A)         → Probability of event A happening (0 to 1)
P(A ∩ B)     → Probability of both A AND B happening
P(A ∪ B)     → Probability of A OR B happening
P(A|B)       → Probability of A GIVEN that B happened (conditional probability)
```

#### Bayes' Theorem

```
P(A|B) = P(B|A) × P(A) / P(B)
```

**In plain English:** "The probability of A given B equals the probability of B given A, times the probability of A, divided by the probability of B."

**Example:** If a medical test is 99% accurate, and 1% of people have the disease, what is the probability you actually have the disease if you test positive?

This is the foundation of the **Naive Bayes** classifier (Day 12).

### 🎯 Day 6 Practice

- Compute the dot product and matrix multiplication of two matrices using NumPy.
- Calculate mean, median, variance, and standard deviation for a dataset.
- Compute and plot a correlation matrix for a dataset.

---

## Day 7: Data Preprocessing — The Most Important Step in ML

### Why Preprocessing?

> "Garbage in, garbage out."

Real-world data is messy. It has:
- Missing values
- Inconsistent formats
- Outliers
- Features on different scales
- Categorical (text) data that models can't understand

**80% of a data scientist's time** is spent on data preprocessing.

### Step 1: Handling Missing Values

```python
import pandas as pd
import numpy as np

df = pd.read_csv("dataset.csv")

# Check missing values
print(df.isnull().sum())

# Strategy 1: Remove rows with missing values
df_clean = df.dropna()

# Strategy 2: Fill with mean (for numerical columns)
df["Age"].fillna(df["Age"].mean(), inplace=True)

# Strategy 3: Fill with median (robust to outliers)
df["Salary"].fillna(df["Salary"].median(), inplace=True)

# Strategy 4: Fill with mode (for categorical columns)
df["City"].fillna(df["City"].mode()[0], inplace=True)

# Strategy 5: Fill with a constant
df["Notes"].fillna("Unknown", inplace=True)
```

**When to use which:**
| Strategy | When to Use |
|----------|-------------|
| Drop rows | Very few rows have missing data (<5%) |
| Mean | Numerical data without outliers |
| Median | Numerical data with outliers |
| Mode | Categorical data |
| Constant | You have domain knowledge of what the value should be |

### Step 2: Encoding Categorical Variables

ML models need **numbers**, not text. We must convert categorical data.

#### Label Encoding (for ordinal data — data with a natural order)

```python
from sklearn.preprocessing import LabelEncoder

# Ordinal data: has a natural order
sizes = ["Small", "Medium", "Large", "Medium", "Small"]
encoder = LabelEncoder()
encoded = encoder.fit_transform(sizes)
print(encoded)  # [2, 1, 0, 1, 2]
# Small=2, Medium=1, Large=0
```

#### One-Hot Encoding (for nominal data — no natural order)

```python
import pandas as pd

# Nominal data: no natural order
df = pd.DataFrame({"Color": ["Red", "Blue", "Green", "Red", "Blue"]})
encoded = pd.get_dummies(df, columns=["Color"])
print(encoded)
#    Color_Blue  Color_Green  Color_Red
# 0       0           0           1
# 1       1           0           0
# 2       0           1           0
# 3       0           0           1
# 4       1           0           0
```

**Why One-Hot?** If we used Label Encoding (Red=0, Blue=1, Green=2), the model might think Green > Blue > Red, which is meaningless for colors.

### Step 3: Feature Scaling

Different features have different scales. This can cause problems for many ML algorithms.

#### Standardization (Z-score Normalization)

Transforms data to have **mean = 0** and **standard deviation = 1**.

```python
from sklearn.preprocessing import StandardScaler

scaler = StandardScaler()
data = [[100, 0.5], [200, 0.8], [150, 0.6]]
scaled = scaler.fit_transform(data)
# Each column now has mean ≈ 0 and std ≈ 1
```

**When to use:** Most ML algorithms (SVM, KNN, Neural Networks, PCA)

#### Min-Max Normalization

Transforms data to a range of **[0, 1]**.

```python
from sklearn.preprocessing import MinMaxScaler

scaler = MinMaxScaler()
data = [[100, 0.5], [200, 0.8], [150, 0.6]]
scaled = scaler.fit_transform(data)
# Each column now ranges from 0 to 1
```

**When to use:** Neural Networks, image data, algorithms that need bounded input.

### Step 4: Train-Test Split

You **never** evaluate a model on the same data it was trained on. Always split your data.

```python
from sklearn.model_selection import train_test_split

X = df[["Age", "Salary", "Experience"]]  # Features
y = df["Hired"]                           # Target

# Split: 80% training, 20% testing
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42
)

print(f"Training samples: {len(X_train)}")
print(f"Testing samples:  {len(X_test)}")
```

**Why `random_state=42`?** It ensures reproducibility — you get the same split every time you run the code.

### Step 5: Handling Outliers

Outliers are extreme values that can distort your model.

```python
import numpy as np

# Method 1: IQR (Interquartile Range) method
Q1 = df["Salary"].quantile(0.25)
Q3 = df["Salary"].quantile(0.75)
IQR = Q3 - Q1

lower_bound = Q1 - 1.5 * IQR
upper_bound = Q3 + 1.5 * IQR

# Filter outliers
df_no_outliers = df[(df["Salary"] >= lower_bound) & (df["Salary"] <= upper_bound)]

# Method 2: Z-score method
from scipy import stats
z_scores = np.abs(stats.zscore(df["Salary"]))
df_no_outliers = df[z_scores < 3]  # Keep only data within 3 std deviations
```

### Complete Preprocessing Pipeline Example

```python
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler, LabelEncoder

# 1. Load data
df = pd.read_csv("employees.csv")

# 2. Handle missing values
df["Age"].fillna(df["Age"].median(), inplace=True)
df["Department"].fillna("Unknown", inplace=True)

# 3. Encode categorical variables
df = pd.get_dummies(df, columns=["Department"], drop_first=True)

# 4. Separate features and target
X = df.drop("Hired", axis=1)
y = df["Hired"]

# 5. Split data
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# 6. Scale features
scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)  # Use transform, NOT fit_transform!
```

> ⚠️ **Critical Note:** Always `fit` the scaler on **training data only**, then `transform` both training and test data. If you fit on test data, you introduce **data leakage** — the model gets information about the test set, which invalidates your evaluation.

### 🎯 Day 7 Practice

- Load a real dataset and perform the full preprocessing pipeline.
- Practice handling missing values with different strategies.
- Apply both StandardScaler and MinMaxScaler and compare the results.
- Split data into train/test sets and verify the split sizes.

---

---

# 🗓️ WEEK 2: Core ML Algorithms (Supervised Learning)

---

## Day 8: Linear Regression — Your First ML Algorithm

### What is Linear Regression?

Linear Regression is the simplest ML algorithm. It finds the **best straight line** that fits your data.

**Goal:** Predict a continuous numerical value (e.g., house price, temperature, salary).

### The Concept (Intuitive Explanation)

Imagine you have data about house sizes and their prices:

```
Size (sq ft)  |  Price ($)
1000          |  200,000
1500          |  300,000
2000          |  400,000
2500          |  500,000
```

If you plot these points, they form roughly a straight line. Linear regression finds the **equation of that line**:

```
Price = (slope × Size) + intercept
Price = (200 × Size) + 0

So for a 1800 sq ft house:
Price = 200 × 1800 = $360,000
```

### The Math

The equation of a straight line:

```
ŷ = w₁x₁ + w₂x₂ + ... + wₙxₙ + b

Where:
  ŷ  = predicted value
  x  = input features
  w  = weights (how much each feature matters)
  b  = bias (the y-intercept)
```

For simple linear regression (one feature):
```
ŷ = wx + b
```

### How Does It Learn? (The Cost Function)

The model starts with random weights and adjusts them to minimize the **error** — the difference between predicted and actual values.

**Mean Squared Error (MSE):**

```
MSE = (1/n) × Σ(yᵢ - ŷᵢ)²

Where:
  n  = number of data points
  yᵢ = actual value
  ŷᵢ = predicted value
```

**Why squared?**
- Squaring makes all errors positive (negative errors don't cancel out positive ones).
- Squaring penalizes larger errors more heavily.

### Gradient Descent (How the Model Improves)

Gradient Descent is the **optimization algorithm** that minimizes the cost function.

**Analogy:** Imagine you're blindfolded on a hilly landscape and need to find the lowest point (valley). You feel the slope under your feet:
- If the ground slopes down to the left → step left.
- If the ground slopes down to the right → step right.
- Keep stepping in the downhill direction until you reach the bottom.

**The process:**
1. Start with random weights.
2. Calculate the error (MSE).
3. Calculate the gradient (slope) of the error with respect to each weight.
4. Update the weights by a small step in the direction that reduces the error.
5. Repeat until the error is minimized.

```
w_new = w_old - learning_rate × gradient
```

**Learning Rate (α):** Controls the size of each step.
- Too large → you might overshoot the minimum (like taking giant steps and jumping over the valley).
- Too small → training takes forever (like taking tiny baby steps).
- Just right → you converge efficiently to the minimum.

### Implementation with Scikit-learn

```python
import numpy as np
import matplotlib.pyplot as plt
from sklearn.linear_model import LinearRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error, r2_score

# Generate sample data
np.random.seed(42)
X = 2 * np.random.rand(100, 1)           # 100 random values between 0 and 2
y = 4 + 3 * X + np.random.randn(100, 1)  # y = 4 + 3x + noise

# Split data
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# Create and train the model
model = LinearRegression()
model.fit(X_train, y_train)

# Model parameters
print(f"Weight (slope): {model.coef_[0][0]:.2f}")       # Should be ≈ 3
print(f"Bias (intercept): {model.intercept_[0]:.2f}")    # Should be ≈ 4

# Make predictions
y_pred = model.predict(X_test)

# Evaluate
mse = mean_squared_error(y_test, y_pred)
r2 = r2_score(y_test, y_pred)
print(f"Mean Squared Error: {mse:.2f}")
print(f"R² Score: {r2:.2f}")  # 1.0 = perfect, 0.0 = terrible

# Visualize
plt.scatter(X_test, y_test, color='blue', label='Actual')
plt.plot(X_test, y_pred, color='red', linewidth=2, label='Predicted')
plt.title("Linear Regression")
plt.xlabel("X")
plt.ylabel("y")
plt.legend()
plt.show()
```

### R² Score (Coefficient of Determination)

R² measures **how well the model explains the variance** in the data.

```
R² = 1 - (Sum of Squared Residuals / Total Sum of Squares)

Interpretation:
  R² = 1.0  → Perfect prediction
  R² = 0.5  → Model explains 50% of the variance
  R² = 0.0  → Model is no better than predicting the mean
  R² < 0    → Model is worse than just predicting the mean
```

### Multiple Linear Regression

When you have **multiple features** (e.g., size, bedrooms, age of house):

```python
# Multiple features
X = df[["Size", "Bedrooms", "Age", "Distance_to_City"]]
y = df["Price"]

model = LinearRegression()
model.fit(X_train, y_train)

# Each feature gets its own weight
print("Weights:", model.coef_)
print("Bias:", model.intercept_)
```

### Assumptions of Linear Regression

For linear regression to work well, these should hold:

1. **Linearity** — The relationship between X and y is linear.
2. **Independence** — Data points are independent of each other.
3. **Homoscedasticity** — The variance of errors is constant.
4. **Normality** — The errors are normally distributed.
5. **No multicollinearity** — Features are not highly correlated with each other.

### 🎯 Day 8 Practice

- Implement linear regression on a dataset (e.g., Boston Housing).
- Plot the regression line.
- Calculate MSE and R².
- Try adding/removing features and see how R² changes.

---

## Day 9: Logistic Regression — Classification, Not Regression!

### What is Logistic Regression?

Despite its name, Logistic Regression is a **classification** algorithm. It predicts the **probability** that something belongs to a category.

**Examples:**
- Is this email spam or not? (Binary: Yes/No)
- Will this customer buy or not? (Binary: Yes/No)
- Is this tumor malignant or benign? (Binary: Malignant/Benign)

### Why Not Use Linear Regression for Classification?

Linear regression predicts values from -∞ to +∞. But probabilities must be between 0 and 1.

If we use linear regression for "Will it rain? (0=No, 1=Yes)", we might get predictions like -0.5 or 1.7, which make no sense as probabilities.

### The Sigmoid Function

Logistic Regression solves this by using the **sigmoid function** (also called the logistic function):

```
σ(z) = 1 / (1 + e^(-z))

Where:
  z = w₁x₁ + w₂x₂ + ... + wₙxₙ + b  (same as linear regression)
  e = Euler's number ≈ 2.718
```

**Properties of the sigmoid function:**
- Output is always between **0 and 1** (perfect for probabilities!)
- When z = 0 → σ(z) = 0.5
- When z → +∞ → σ(z) → 1
- When z → -∞ → σ(z) → 0
- It creates an **S-shaped curve**

```python
import numpy as np
import matplotlib.pyplot as plt

z = np.linspace(-10, 10, 100)
sigmoid = 1 / (1 + np.exp(-z))

plt.figure(figsize=(10, 6))
plt.plot(z, sigmoid, linewidth=2)
plt.title("Sigmoid Function", fontsize=16)
plt.xlabel("z")
plt.ylabel("σ(z)")
plt.axhline(y=0.5, color='r', linestyle='--', alpha=0.5)
plt.axvline(x=0, color='g', linestyle='--', alpha=0.5)
plt.grid(True, alpha=0.3)
plt.show()
```

### Decision Boundary

The sigmoid outputs a probability. We need a **threshold** to make a final prediction:

```
If σ(z) ≥ 0.5 → Predict Class 1 (e.g., spam)
If σ(z) < 0.5 → Predict Class 0 (e.g., not spam)
```

The **decision boundary** is the line (or surface) where the model is exactly 50-50.

### Cost Function: Log Loss (Binary Cross-Entropy)

We can't use MSE for logistic regression (it creates a non-convex function with many local minima). Instead, we use **Log Loss**:

```
Cost = -(1/n) × Σ [yᵢ × log(ŷᵢ) + (1 - yᵢ) × log(1 - ŷᵢ)]

When actual = 1: Cost = -log(ŷ)    → penalizes low predictions
When actual = 0: Cost = -log(1-ŷ)  → penalizes high predictions
```

### Implementation

```python
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, confusion_matrix, classification_report
from sklearn.datasets import load_breast_cancer
import pandas as pd

# Load dataset
data = load_breast_cancer()
X = pd.DataFrame(data.data, columns=data.feature_names)
y = data.target  # 0 = malignant, 1 = benign

# Split
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# Train
model = LogisticRegression(max_iter=10000)
model.fit(X_train, y_train)

# Predict
y_pred = model.predict(X_test)

# Evaluate
print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")
print(f"\nConfusion Matrix:\n{confusion_matrix(y_test, y_pred)}")
print(f"\nClassification Report:\n{classification_report(y_test, y_pred)}")
```

### Understanding the Confusion Matrix

```
                    Predicted
                  Negative  Positive
Actual Negative [   TN    |   FP   ]
Actual Positive [   FN    |   TP   ]

TN = True Negative  — correctly predicted negative
FP = False Positive — incorrectly predicted positive (Type I Error)
FN = False Negative — incorrectly predicted negative (Type II Error)
TP = True Positive  — correctly predicted positive
```

### Classification Metrics

```
Accuracy  = (TP + TN) / (TP + TN + FP + FN)    — overall correctness
Precision = TP / (TP + FP)                       — "of all positive predictions, how many were correct?"
Recall    = TP / (TP + FN)                       — "of all actual positives, how many did we find?"
F1 Score  = 2 × (Precision × Recall) / (Precision + Recall)  — harmonic mean of precision & recall
```

**When to prioritize which metric:**
| Metric | Prioritize When | Example |
|--------|----------------|---------|
| Precision | False positives are costly | Spam filter (don't want to miss important emails) |
| Recall | False negatives are costly | Cancer detection (don't want to miss a diagnosis) |
| F1 Score | You need a balance | Most general-purpose classifiers |

### 🎯 Day 9 Practice

- Implement logistic regression on the breast cancer dataset.
- Plot the confusion matrix.
- Experiment with different thresholds (not just 0.5) and see how precision/recall change.

---

## Day 10: Decision Trees — Intuitive, Visual, Powerful

### What is a Decision Tree?

A Decision Tree makes predictions by asking a **series of yes/no questions** about the features, splitting the data at each step until it reaches a prediction.

**Analogy:** It's like a game of "20 Questions":
```
Is the animal a mammal?
├── Yes: Does it have fur?
│   ├── Yes: Is it bigger than a cat?
│   │   ├── Yes → Dog
│   │   └── No → Cat
│   └── No → Dolphin
└── No: Does it have wings?
    ├── Yes → Bird
    └── No → Fish
```

### How Does It Work?

The algorithm builds the tree **top-down** by:
1. Looking at **all possible ways** to split the data.
2. Choosing the split that creates the **purest** groups (groups where data points are most similar).
3. Repeating for each subgroup until:
   - All data points in a group belong to the same class (pure), or
   - A stopping condition is met (max depth, min samples, etc.).

### Splitting Criteria

#### For Classification: Gini Impurity and Entropy

**Gini Impurity** measures how often a randomly chosen element would be misclassified:

```
Gini = 1 - Σ(pᵢ²)

Where pᵢ = probability of class i

Example: A node with 50% class A and 50% class B:
Gini = 1 - (0.5² + 0.5²) = 1 - 0.5 = 0.5  (maximum impurity)

A pure node (100% class A):
Gini = 1 - (1.0²) = 0  (no impurity — perfect!)
```

**Entropy** (from Information Theory):

```
Entropy = -Σ(pᵢ × log₂(pᵢ))

A pure node: Entropy = 0
Maximum impurity (50-50): Entropy = 1
```

**Information Gain** = (Entropy before split) - (Weighted Entropy after split)

The tree picks the split with the **highest information gain**.

#### For Regression: Variance Reduction

Instead of Gini/Entropy, regression trees minimize the **variance** (or MSE) within each split.

### Implementation

```python
from sklearn.tree import DecisionTreeClassifier, plot_tree
from sklearn.datasets import load_iris
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score
import matplotlib.pyplot as plt

# Load famous Iris dataset
iris = load_iris()
X = iris.data
y = iris.target

# Split
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# Train
tree = DecisionTreeClassifier(max_depth=3, random_state=42)
tree.fit(X_train, y_train)

# Evaluate
y_pred = tree.predict(X_test)
print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")

# Visualize the tree!
plt.figure(figsize=(20, 10))
plot_tree(tree, feature_names=iris.feature_names, class_names=iris.target_names,
          filled=True, rounded=True, fontsize=10)
plt.title("Decision Tree — Iris Dataset")
plt.show()
```

### Hyperparameters (Controls to Prevent Overfitting)

| Parameter | Description | Effect |
|-----------|-------------|--------|
| `max_depth` | Maximum depth of the tree | Lower = simpler tree, less overfitting |
| `min_samples_split` | Minimum samples needed to split a node | Higher = simpler tree |
| `min_samples_leaf` | Minimum samples in a leaf node | Higher = simpler tree |
| `max_features` | Number of features to consider for each split | Lower = more randomness |

### Pros and Cons

| ✅ Pros | ❌ Cons |
|---------|---------|
| Easy to understand and visualize | Prone to overfitting |
| No feature scaling needed | Small changes in data can create very different trees |
| Handles both numerical and categorical data | Can create biased trees with imbalanced data |
| Fast to train and predict | |

### 🎯 Day 10 Practice

- Train a decision tree on the Iris dataset and visualize it.
- Experiment with different `max_depth` values and see how accuracy changes.
- Compare Gini and Entropy as splitting criteria.

---

## Day 11: Random Forest & Ensemble Methods

### The Problem with Single Decision Trees

A single decision tree tends to **overfit** — it memorizes the training data too closely and performs poorly on new data. Small changes in the data can produce completely different trees.

### The Solution: Ensemble Methods

**Ensemble methods** combine multiple "weak" models to create a "strong" model.

**Analogy:** If you ask one person for a restaurant recommendation, you might get a biased answer. But if you ask 100 people and take the majority vote, you'll likely get a better recommendation.

### Random Forest

A Random Forest is a collection of **many decision trees**, each trained on a slightly different version of the data.

**How it works:**

1. **Bagging (Bootstrap Aggregating):**
   - Create N random subsets of the training data (sampling **with replacement**).
   - With replacement means the same data point can appear multiple times in a subset.

2. **Feature Randomness:**
   - At each split, consider only a random subset of features (not all features).
   - This ensures the trees are **diverse** — they look at different aspects of the data.

3. **Aggregation:**
   - **Classification:** Each tree votes, and the majority wins.
   - **Regression:** Average the predictions from all trees.

```python
from sklearn.ensemble import RandomForestClassifier
from sklearn.datasets import load_wine
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, classification_report

# Load data
wine = load_wine()
X_train, X_test, y_train, y_test = train_test_split(
    wine.data, wine.target, test_size=0.2, random_state=42
)

# Train Random Forest
rf = RandomForestClassifier(
    n_estimators=100,       # Number of trees
    max_depth=5,            # Max depth of each tree
    random_state=42
)
rf.fit(X_train, y_train)

# Predict & evaluate
y_pred = rf.predict(X_test)
print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")

# Feature importance — which features matter most?
import pandas as pd
importance = pd.Series(rf.feature_importances_, index=wine.feature_names)
importance.sort_values(ascending=True).plot(kind='barh', figsize=(10, 8))
plt.title("Feature Importance — Random Forest")
plt.show()
```

### Other Ensemble Methods

#### Gradient Boosting

Instead of training trees in parallel (like Random Forest), Gradient Boosting trains trees **sequentially** — each new tree focuses on correcting the errors of the previous one.

```
Tree 1 → makes predictions → finds errors
Tree 2 → focuses on the errors from Tree 1 → makes new predictions
Tree 3 → focuses on the errors from Tree 1+2 → ...
...
Final prediction = sum of all trees' predictions
```

```python
from sklearn.ensemble import GradientBoostingClassifier

gb = GradientBoostingClassifier(
    n_estimators=100,
    learning_rate=0.1,    # Step size for each tree's contribution
    max_depth=3,
    random_state=42
)
gb.fit(X_train, y_train)
print(f"Accuracy: {accuracy_score(y_test, gb.predict(X_test)):.4f}")
```

#### XGBoost (eXtreme Gradient Boosting)

An optimized implementation of gradient boosting. It's the **#1 algorithm for tabular data** and wins most Kaggle competitions.

```python
# Install: pip install xgboost
from xgboost import XGBClassifier

xgb = XGBClassifier(
    n_estimators=100,
    learning_rate=0.1,
    max_depth=3,
    random_state=42
)
xgb.fit(X_train, y_train)
print(f"Accuracy: {accuracy_score(y_test, xgb.predict(X_test)):.4f}")
```

### Comparison: Random Forest vs Gradient Boosting

| Aspect | Random Forest | Gradient Boosting |
|--------|---------------|-------------------|
| Training | Parallel (fast) | Sequential (slower) |
| Overfitting | Less prone | More prone (needs careful tuning) |
| Accuracy | Good | Often better (with tuning) |
| Ease of Use | Easy (few hyperparameters) | Requires more tuning |
| Best for | Quick baseline | Maximum performance |

### 🎯 Day 11 Practice

- Train Random Forest and compare accuracy to a single decision tree.
- Plot feature importance.
- Train XGBoost and compare it to Random Forest.
- Experiment with `n_estimators` and `max_depth`.

---

## Day 12: K-Nearest Neighbors (KNN) & Naive Bayes

### K-Nearest Neighbors (KNN)

#### The Concept

KNN is the **simplest** ML algorithm. It doesn't actually "learn" anything — it just memorizes all training data and makes predictions based on **similarity**.

**Analogy:** You just moved to a new neighborhood. To guess how much your house is worth, you look at the prices of the K nearest houses. If the 5 nearest houses are priced at $200K, $210K, $205K, $215K, $200K, you'd estimate yours at about $206K.

#### How It Works

1. Choose a value for K (number of neighbors to consider).
2. For a new data point, calculate the **distance** to all training data points.
3. Find the **K nearest** (closest) data points.
4. **Classification:** Take the majority class among the K neighbors.
5. **Regression:** Take the average value of the K neighbors.

#### Distance Metrics

**Euclidean Distance** (most common):
```
d = √[(x₁-x₂)² + (y₁-y₂)² + ... + (zₙ-zₙ)²]
```

**Manhattan Distance** (sum of absolute differences):
```
d = |x₁-x₂| + |y₁-y₂| + ... + |zₙ-zₙ|
```

#### Choosing K

- **K too small (e.g., K=1):** Noisy, overfits (decision boundary is too jagged).
- **K too large (e.g., K=100):** Underfits (decision boundary is too smooth, ignores local patterns).
- **Rule of thumb:** Start with K = √n (where n = number of training samples).
- **Best practice:** Try multiple K values and pick the one with the best validation score.

```python
from sklearn.neighbors import KNeighborsClassifier
from sklearn.datasets import load_iris
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score
from sklearn.preprocessing import StandardScaler

# Load and prepare data
iris = load_iris()
X_train, X_test, y_train, y_test = train_test_split(
    iris.data, iris.target, test_size=0.2, random_state=42
)

# IMPORTANT: KNN is distance-based, so ALWAYS scale your data!
scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

# Train and evaluate
knn = KNeighborsClassifier(n_neighbors=5)
knn.fit(X_train_scaled, y_train)
print(f"Accuracy: {accuracy_score(y_test, knn.predict(X_test_scaled)):.4f}")

# Try different K values
for k in range(1, 21):
    knn = KNeighborsClassifier(n_neighbors=k)
    knn.fit(X_train_scaled, y_train)
    acc = accuracy_score(y_test, knn.predict(X_test_scaled))
    print(f"K={k:2d} → Accuracy: {acc:.4f}")
```

#### Pros and Cons of KNN

| ✅ Pros | ❌ Cons |
|---------|---------|
| Simple to understand | Slow on large datasets (must compute distance to all points) |
| No training time | Requires feature scaling |
| Good for multi-class problems | Sensitive to irrelevant features |
| Non-parametric (no assumptions) | Memory-intensive (stores all training data) |

---

### Naive Bayes

#### The Concept

Naive Bayes is based on **Bayes' Theorem** and is called "naive" because it assumes all features are **independent** of each other (which is rarely true in practice, but works surprisingly well).

#### Bayes' Theorem Applied to Classification

```
P(Class | Features) = P(Features | Class) × P(Class) / P(Features)

In words:
"Probability of this being class A given these features"
= "Probability of seeing these features in class A" × "Probability of class A"
  ÷ "Probability of these features in general"
```

#### Example: Spam Detection

Consider classifying emails as spam or not spam based on words:

```
Email: "Win free money now!"

P(Spam | "Win free money now")
= P("Win free money now" | Spam) × P(Spam) / P("Win free money now")

The "naive" assumption: each word is independent
= P("Win"|Spam) × P("free"|Spam) × P("money"|Spam) × P("now"|Spam) × P(Spam)
  ÷ P("Win free money now")
```

#### Types of Naive Bayes

| Type | Use When |
|------|----------|
| **GaussianNB** | Features are continuous (e.g., height, weight) |
| **MultinomialNB** | Features are counts (e.g., word counts in text) |
| **BernoulliNB** | Features are binary (e.g., word present/absent) |

```python
from sklearn.naive_bayes import GaussianNB
from sklearn.datasets import load_iris
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score

iris = load_iris()
X_train, X_test, y_train, y_test = train_test_split(
    iris.data, iris.target, test_size=0.2, random_state=42
)

nb = GaussianNB()
nb.fit(X_train, y_train)
print(f"Accuracy: {accuracy_score(y_test, nb.predict(X_test)):.4f}")
```

### 🎯 Day 12 Practice

- Implement KNN on the Iris dataset. Plot accuracy vs K value.
- Implement Naive Bayes and compare it to KNN and Decision Tree.
- Try KNN without scaling and observe the difference.

---

## Day 13: Support Vector Machines (SVM)

### What is SVM?

A Support Vector Machine finds the **best boundary (hyperplane)** that separates different classes with the **maximum margin**.

### The Concept (Intuitive Explanation)

Imagine you have two groups of points on a 2D plane — red and blue. There are many lines that could separate them. SVM finds the line that:
1. Correctly separates the two classes.
2. Is **as far as possible** from the nearest points of each class.

The **margin** is the distance between the hyperplane and the nearest data points. SVM maximizes this margin.

The data points that are closest to the boundary are called **support vectors** — they literally "support" (define) the boundary.

```
Blue points:  ●  ●  ●          |          ○  ○  ○  :Red points
                    ●          |                 ○
                  ●     ←margin→|←margin→     ○
                               |
              Support Vector → | ← Support Vector
                               |
                          (Hyperplane)
```

### The Kernel Trick

What if the data **isn't linearly separable** — i.e., you can't draw a straight line to separate the classes?

SVM uses the **kernel trick** to transform data into a higher-dimensional space where it becomes linearly separable.

**Analogy:** Imagine red and blue balls on a table, mixed in a circular pattern (red in the center, blue around). You can't separate them with a straight line on the table. But if you lift the center up (adding a 3rd dimension), you can now place a flat sheet between them!

#### Common Kernels

| Kernel | When to Use |
|--------|-------------|
| **Linear** | Data is linearly separable |
| **RBF (Radial Basis Function)** | Default choice, works well for most non-linear problems |
| **Polynomial** | Data has polynomial relationships |

### Key Hyperparameters

#### C (Regularization Parameter)
- **Small C:** Wider margin, more misclassifications allowed (simpler model, may underfit).
- **Large C:** Narrower margin, fewer misclassifications (complex model, may overfit).

#### Gamma (for RBF kernel)
- **Small gamma:** Each point's influence reaches far (smooth decision boundary).
- **Large gamma:** Each point's influence is local (complex, wiggly boundary).

### Implementation

```python
from sklearn.svm import SVC
from sklearn.datasets import load_breast_cancer
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, classification_report

# Load data
data = load_breast_cancer()
X_train, X_test, y_train, y_test = train_test_split(
    data.data, data.target, test_size=0.2, random_state=42
)

# Scale (IMPORTANT for SVM!)
scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

# Train SVM with RBF kernel
svm = SVC(kernel='rbf', C=1.0, gamma='scale', random_state=42)
svm.fit(X_train_scaled, y_train)

# Evaluate
y_pred = svm.predict(X_test_scaled)
print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")
print(classification_report(y_test, y_pred))

# Try different kernels
for kernel in ['linear', 'rbf', 'poly']:
    svm = SVC(kernel=kernel, random_state=42)
    svm.fit(X_train_scaled, y_train)
    acc = accuracy_score(y_test, svm.predict(X_test_scaled))
    print(f"Kernel: {kernel:8s} → Accuracy: {acc:.4f}")
```

### Pros and Cons

| ✅ Pros | ❌ Cons |
|---------|---------|
| Effective in high-dimensional spaces | Slow on large datasets |
| Works well with clear margins | Doesn't perform well when classes overlap heavily |
| Versatile (different kernels) | Requires feature scaling |
| Memory efficient (uses only support vectors) | Not great for very noisy data |

### 🎯 Day 13 Practice

- Train SVM on the breast cancer dataset with different kernels.
- Experiment with C and gamma values.
- Compare SVM accuracy to Logistic Regression and Random Forest.

---

## Day 14: Model Evaluation & Cross-Validation

### Why Proper Evaluation Matters

A model that performs well on training data but poorly on new data is **useless**. Proper evaluation tells you how well your model will perform in the real world.

### Train/Validation/Test Split

```
Total Dataset
├── Training Set (60-70%)    → Used to TRAIN the model
├── Validation Set (15-20%)  → Used to TUNE hyperparameters
└── Test Set (15-20%)        → Used for FINAL evaluation (touch ONCE!)

         The test set is like the final exam:
         you only take it ONCE after you've finished studying.
```

### K-Fold Cross-Validation

Instead of a single train/validation split, K-Fold Cross-Validation:
1. Splits the data into **K equal folds**.
2. Trains on K-1 folds and validates on the remaining 1 fold.
3. Repeats K times, each time using a different fold as validation.
4. Averages the K scores for a more reliable estimate.

```
5-Fold Cross-Validation:

Fold 1: [VAL] [Train] [Train] [Train] [Train]
Fold 2: [Train] [VAL] [Train] [Train] [Train]
Fold 3: [Train] [Train] [VAL] [Train] [Train]
Fold 4: [Train] [Train] [Train] [VAL] [Train]
Fold 5: [Train] [Train] [Train] [Train] [VAL]

Final score = average of 5 validation scores
```

```python
from sklearn.model_selection import cross_val_score
from sklearn.ensemble import RandomForestClassifier
from sklearn.datasets import load_iris

iris = load_iris()

rf = RandomForestClassifier(n_estimators=100, random_state=42)

# 5-fold cross-validation
scores = cross_val_score(rf, iris.data, iris.target, cv=5, scoring='accuracy')
print(f"Fold scores: {scores}")
print(f"Mean accuracy: {scores.mean():.4f} ± {scores.std():.4f}")
```

### Hyperparameter Tuning with Grid Search

**Grid Search** tries every combination of hyperparameters and picks the best one.

```python
from sklearn.model_selection import GridSearchCV
from sklearn.svm import SVC

# Define hyperparameter grid
param_grid = {
    'C': [0.1, 1, 10, 100],
    'gamma': ['scale', 'auto', 0.1, 0.01],
    'kernel': ['rbf', 'linear']
}

# Grid search with 5-fold cross-validation
grid_search = GridSearchCV(
    SVC(),
    param_grid,
    cv=5,
    scoring='accuracy',
    verbose=1,
    n_jobs=-1  # Use all CPU cores
)

grid_search.fit(X_train_scaled, y_train)

print(f"Best parameters: {grid_search.best_params_}")
print(f"Best CV score: {grid_search.best_score_:.4f}")
print(f"Test score: {grid_search.score(X_test_scaled, y_test):.4f}")
```

### Bias-Variance Tradeoff

This is one of the most fundamental concepts in ML.

```
                    Model Complexity →
         Simple ←——————————————————→ Complex

Error
  ↑
  |  ╲                     ╱  ← Total Error
  |    ╲                 ╱
  |      ╲    ╌╌╌╌╌╌╌╌╌    ← Variance (overfitting)
  |        ╲╱
  |       ╱  ╲
  |     ╱      ╲
  |   ╱     ╌╌╌╌ ╲╌╌╌╌    ← Bias (underfitting)
  |  ╱
  └———————————————————————→

          Sweet Spot (ideal complexity)
```

- **Bias:** Error from oversimplifying (underfitting). The model misses patterns.
- **Variance:** Error from overcomplexity (overfitting). The model memorizes noise.
- **Goal:** Find the sweet spot where total error (bias + variance) is minimized.

### Learning Curves

Learning curves help you diagnose bias vs variance.

```python
from sklearn.model_selection import learning_curve
import matplotlib.pyplot as plt
import numpy as np

train_sizes, train_scores, val_scores = learning_curve(
    RandomForestClassifier(n_estimators=100, random_state=42),
    iris.data, iris.target,
    train_sizes=np.linspace(0.1, 1.0, 10),
    cv=5, scoring='accuracy'
)

plt.figure(figsize=(10, 6))
plt.plot(train_sizes, train_scores.mean(axis=1), label='Training Score')
plt.plot(train_sizes, val_scores.mean(axis=1), label='Validation Score')
plt.title("Learning Curve")
plt.xlabel("Training Set Size")
plt.ylabel("Accuracy")
plt.legend()
plt.grid(True, alpha=0.3)
plt.show()
```

**How to read learning curves:**
- **High bias (underfitting):** Both training and validation scores are low and close together.
- **High variance (overfitting):** Training score is high, but validation score is much lower (big gap).
- **Good fit:** Both scores are high and close together.

### ROC Curve and AUC

The **ROC (Receiver Operating Characteristic) Curve** plots the trade-off between:
- **True Positive Rate (Recall):** How many actual positives did we catch?
- **False Positive Rate:** How many negatives did we incorrectly label as positive?

**AUC (Area Under the Curve):**
- AUC = 1.0 → perfect model
- AUC = 0.5 → random guessing (useless model)
- AUC < 0.5 → worse than random (something is wrong)

```python
from sklearn.metrics import roc_curve, auc
from sklearn.linear_model import LogisticRegression

model = LogisticRegression(max_iter=10000)
model.fit(X_train_scaled, y_train)

# Get probability scores
y_prob = model.predict_proba(X_test_scaled)[:, 1]

# Compute ROC
fpr, tpr, thresholds = roc_curve(y_test, y_prob)
roc_auc = auc(fpr, tpr)

# Plot
plt.figure(figsize=(8, 6))
plt.plot(fpr, tpr, color='blue', linewidth=2, label=f'ROC Curve (AUC = {roc_auc:.2f})')
plt.plot([0, 1], [0, 1], color='red', linestyle='--', label='Random Guess')
plt.title("ROC Curve")
plt.xlabel("False Positive Rate")
plt.ylabel("True Positive Rate")
plt.legend()
plt.grid(True, alpha=0.3)
plt.show()
```

### 🎯 Day 14 Practice

- Perform 5-fold and 10-fold cross-validation on 3 different models. Compare results.
- Use GridSearchCV to tune hyperparameters for SVM and Random Forest.
- Plot learning curves and ROC curves.
- Identify whether your model has high bias or high variance.

---

---

# 🗓️ WEEK 3: Advanced ML Concepts & Unsupervised Learning

---

## Day 15: Feature Engineering — The Art of Creating Better Features

### What is Feature Engineering?

Feature Engineering is the process of **creating new features** or **transforming existing features** to improve model performance. It is often said:

> "Coming up with features is difficult, time-consuming, and requires expert knowledge. Applied machine learning is basically feature engineering." — Andrew Ng

### Why Does It Matter?

A good feature can make a simple model outperform a complex model with bad features.

### Common Feature Engineering Techniques

#### 1. Polynomial Features

Create new features by combining existing ones.

```python
from sklearn.preprocessing import PolynomialFeatures
import numpy as np

X = np.array([[2, 3],
              [4, 5]])

# Degree 2: creates x₁², x₂², x₁×x₂
poly = PolynomialFeatures(degree=2, include_bias=False)
X_poly = poly.fit_transform(X)
# [2, 3] → [2, 3, 4, 6, 9]   (x₁, x₂, x₁², x₁x₂, x₂²)
```

#### 2. Binning (Discretization)

Convert continuous features into categories.

```python
import pandas as pd

df = pd.DataFrame({"Age": [15, 22, 35, 45, 60, 72]})

# Create age bins
df["Age_Group"] = pd.cut(df["Age"], bins=[0, 18, 35, 55, 100],
                          labels=["Youth", "Young Adult", "Middle Age", "Senior"])
```

#### 3. Log Transformation

Used when data is **right-skewed** (has a long tail to the right, like income or house prices).

```python
import numpy as np

# Original: [100, 1000, 10000, 100000] — huge range!
# Log transformed: [2, 3, 4, 5] — much more manageable
df["Log_Salary"] = np.log1p(df["Salary"])  # log1p = log(1+x), handles 0s
```

#### 4. Date/Time Features

Extract useful information from datetime columns.

```python
df["Date"] = pd.to_datetime(df["Date"])
df["Year"] = df["Date"].dt.year
df["Month"] = df["Date"].dt.month
df["DayOfWeek"] = df["Date"].dt.dayofweek    # 0=Monday, 6=Sunday
df["IsWeekend"] = df["DayOfWeek"].isin([5, 6]).astype(int)
df["Quarter"] = df["Date"].dt.quarter
```

#### 5. Text Features

```python
# Length of text
df["Text_Length"] = df["Review"].str.len()

# Word count
df["Word_Count"] = df["Review"].str.split().str.len()

# Contains specific word
df["Has_Excellent"] = df["Review"].str.contains("excellent", case=False).astype(int)
```

#### 6. Interaction Features

Create features by combining two features.

```python
df["Price_per_SqFt"] = df["Price"] / df["SquareFeet"]
df["Room_Density"] = df["Rooms"] / df["SquareFeet"]
df["BMI"] = df["Weight"] / (df["Height"] ** 2)
```

### Feature Selection

Not all features are useful. Too many features can cause **overfitting** and slow training.

#### Methods for Feature Selection

```python
# Method 1: Correlation with target
correlations = df.corr()["Target"].abs().sort_values(ascending=False)
print(correlations)  # Drop features with very low correlation

# Method 2: Feature importance from Random Forest
from sklearn.ensemble import RandomForestClassifier
rf = RandomForestClassifier(n_estimators=100, random_state=42)
rf.fit(X_train, y_train)
importances = pd.Series(rf.feature_importances_, index=feature_names)
importances.sort_values().plot(kind='barh')

# Method 3: SelectKBest
from sklearn.feature_selection import SelectKBest, f_classif
selector = SelectKBest(f_classif, k=10)  # Keep top 10 features
X_selected = selector.fit_transform(X, y)
```

### 🎯 Day 15 Practice

- Create polynomial features and observe if model accuracy improves.
- Apply log transformation to a skewed feature and compare distributions.
- Use feature importance to select the top features and retrain the model.

---

## Day 16: Regularization — Preventing Overfitting

### What is Regularization?

Regularization adds a **penalty** to the model's complexity to prevent overfitting. It discourages the model from assigning too much importance to any single feature.

### Ridge Regression (L2 Regularization)

Ridge adds the **sum of squared weights** as a penalty:

```
Cost = MSE + α × Σ(wᵢ²)

Where α (alpha) controls the strength of regularization:
  α = 0     → No regularization (standard linear regression)
  α = large → Heavy regularization (weights shrink toward 0)
```

**Effect:** Shrinks all weights toward zero but never makes them exactly zero. All features stay in the model.

```python
from sklearn.linear_model import Ridge

ridge = Ridge(alpha=1.0)
ridge.fit(X_train, y_train)
print(f"R² Score: {ridge.score(X_test, y_test):.4f}")
print(f"Weights: {ridge.coef_}")  # All weights are small but non-zero
```

### Lasso Regression (L1 Regularization)

Lasso adds the **sum of absolute weights** as a penalty:

```
Cost = MSE + α × Σ|wᵢ|
```

**Effect:** Can shrink some weights to **exactly zero**, effectively performing **feature selection**. It removes unimportant features from the model.

```python
from sklearn.linear_model import Lasso

lasso = Lasso(alpha=0.1)
lasso.fit(X_train, y_train)
print(f"R² Score: {lasso.score(X_test, y_test):.4f}")
print(f"Weights: {lasso.coef_}")  # Some weights are EXACTLY 0
print(f"Features used: {np.sum(lasso.coef_ != 0)} out of {len(lasso.coef_)}")
```

### Elastic Net (L1 + L2)

Combines both Ridge and Lasso:

```
Cost = MSE + α₁ × Σ|wᵢ| + α₂ × Σ(wᵢ²)
```

```python
from sklearn.linear_model import ElasticNet

enet = ElasticNet(alpha=0.1, l1_ratio=0.5)  # l1_ratio controls L1 vs L2 balance
enet.fit(X_train, y_train)
```

### Comparison

| Aspect | Ridge (L2) | Lasso (L1) | Elastic Net |
|--------|-----------|-----------|-------------|
| Penalty | Sum of squared weights | Sum of absolute weights | Both |
| Feature Selection | No (keeps all features) | Yes (sets some to 0) | Yes (partial) |
| Best When | All features are relevant | Many irrelevant features | Groups of correlated features |
| Multicollinearity | Handles well | May arbitrarily pick one from correlated features | Handles well |

### Regularization in Other Models

Regularization isn't just for linear models. It appears everywhere in ML:

| Model | Regularization Method |
|-------|----------------------|
| Decision Trees | Max depth, min samples per leaf |
| Random Forest | Number of trees, max features |
| Neural Networks | Dropout, weight decay, early stopping |
| SVM | C parameter (lower C = more regularization) |

### 🎯 Day 16 Practice

- Compare Linear Regression, Ridge, and Lasso on a dataset with many features.
- Plot how the weights change as alpha increases.
- Use Lasso to identify the most important features.

---

## Day 17: K-Means Clustering — Unsupervised Learning Begins

### What is Unsupervised Learning?

Until now, all our algorithms had **labels** (supervised learning). In unsupervised learning, we have **no labels** — the algorithm must find patterns on its own.

### What is Clustering?

Clustering groups similar data points together. It's like sorting a mixed bag of objects into piles without being told what the categories are.

### K-Means Clustering

K-Means is the most popular clustering algorithm.

#### How It Works

1. **Choose K** (the number of clusters).
2. **Initialize** K random cluster centers (centroids).
3. **Assign** each data point to the nearest centroid.
4. **Update** each centroid to be the mean of all points assigned to it.
5. **Repeat** steps 3-4 until centroids stop moving (convergence).

```
Iteration 1:    Iteration 2:    Iteration 3 (Converged):
  ★  ○ ○          ★  ○ ○          ★  ○ ○
  ○              ○ ○             ○ ○
   ○  ●            ○ ●             ○ ●
  ○  ○ ○          ○  ○ ○          ○  ○ ○

★ = Centroid A    ● = Centroid B    ○ = Data points
(Centroids move toward the center of their assigned points)
```

#### Implementation

```python
from sklearn.cluster import KMeans
import numpy as np
import matplotlib.pyplot as plt

# Generate sample data
from sklearn.datasets import make_blobs
X, y_true = make_blobs(n_samples=300, centers=4, cluster_std=0.60, random_state=0)

# Fit K-Means
kmeans = KMeans(n_clusters=4, random_state=42, n_init=10)
y_pred = kmeans.fit_predict(X)

# Visualize
plt.figure(figsize=(10, 6))
plt.scatter(X[:, 0], X[:, 1], c=y_pred, cmap='viridis', alpha=0.5, s=50)
plt.scatter(kmeans.cluster_centers_[:, 0], kmeans.cluster_centers_[:, 1],
            c='red', marker='X', s=200, edgecolors='black', linewidths=2, label='Centroids')
plt.title("K-Means Clustering", fontsize=16)
plt.legend()
plt.show()
```

### How to Choose K? — The Elbow Method

```python
inertias = []
K_range = range(1, 11)

for k in K_range:
    kmeans = KMeans(n_clusters=k, random_state=42, n_init=10)
    kmeans.fit(X)
    inertias.append(kmeans.inertia_)  # Sum of squared distances to nearest centroid

plt.figure(figsize=(10, 6))
plt.plot(K_range, inertias, 'bx-', linewidth=2, markersize=10)
plt.title("Elbow Method", fontsize=16)
plt.xlabel("Number of Clusters (K)")
plt.ylabel("Inertia (Within-cluster sum of squares)")
plt.show()

# The "elbow" point (where the curve bends sharply) is the optimal K
```

### Silhouette Score

A more rigorous way to evaluate clustering quality.

```python
from sklearn.metrics import silhouette_score

for k in range(2, 11):
    kmeans = KMeans(n_clusters=k, random_state=42, n_init=10)
    labels = kmeans.fit_predict(X)
    score = silhouette_score(X, labels)
    print(f"K={k}: Silhouette Score = {score:.4f}")

# Score ranges from -1 to 1:
# 1.0 = perfect clusters
# 0.0 = overlapping clusters
# -1.0 = wrong clusters
```

### Limitations of K-Means

- You must specify K in advance.
- Assumes clusters are spherical and equally sized.
- Sensitive to initialization (use `n_init=10` to run multiple times).
- Sensitive to outliers.

### 🎯 Day 17 Practice

- Cluster a dataset using K-Means with different K values.
- Use the Elbow Method and Silhouette Score to find the optimal K.
- Visualize the clusters and centroids.

---

## Day 18: Hierarchical Clustering & DBSCAN

### Hierarchical Clustering

#### The Concept

Hierarchical Clustering builds a **tree of clusters** (dendrogram). It can be done in two ways:

**Agglomerative (Bottom-Up):**
1. Start with each data point as its own cluster.
2. Merge the two closest clusters.
3. Repeat until all points are in one cluster.

**Divisive (Top-Down):**
1. Start with all data points in one cluster.
2. Split the cluster into two.
3. Repeat until each point is its own cluster.

#### Dendrogram

A dendrogram visualizes the hierarchy of merges. You "cut" the tree at a certain height to get your desired number of clusters.

```python
from scipy.cluster.hierarchy import dendrogram, linkage
import matplotlib.pyplot as plt
import numpy as np

# Generate data
from sklearn.datasets import make_blobs
X, _ = make_blobs(n_samples=50, centers=3, random_state=42)

# Compute linkage
linked = linkage(X, method='ward')

# Plot dendrogram
plt.figure(figsize=(14, 7))
dendrogram(linked, truncate_mode='lastp', p=30)
plt.title("Dendrogram", fontsize=16)
plt.xlabel("Data Points")
plt.ylabel("Distance")
plt.show()
```

```python
from sklearn.cluster import AgglomerativeClustering

# Agglomerative Clustering
agg = AgglomerativeClustering(n_clusters=3, linkage='ward')
labels = agg.fit_predict(X)

plt.scatter(X[:, 0], X[:, 1], c=labels, cmap='viridis', s=50)
plt.title("Hierarchical Clustering")
plt.show()
```

#### Linkage Methods

| Method | How It Measures Distance Between Clusters |
|--------|------------------------------------------|
| **Ward** | Minimizes variance within clusters (most common) |
| **Complete** | Maximum distance between any two points in the clusters |
| **Average** | Average distance between all pairs of points |
| **Single** | Minimum distance between any two points (can create elongated clusters) |

---

### DBSCAN (Density-Based Spatial Clustering)

#### The Concept

DBSCAN finds clusters based on **density** — areas where data points are packed closely together. Unlike K-Means, it:
- **Doesn't require specifying K.**
- **Can find non-spherical clusters** (any shape!).
- **Identifies outliers/noise** as points that don't belong to any cluster.

#### How It Works

Two parameters:
- **eps (ε):** The maximum distance between two points to be considered neighbors.
- **min_samples:** The minimum number of points required to form a cluster.

Three types of points:
- **Core point:** Has at least `min_samples` points within `eps` distance.
- **Border point:** Within `eps` of a core point but doesn't have enough neighbors to be a core point itself.
- **Noise point:** Not within `eps` of any core point → outlier!

```python
from sklearn.cluster import DBSCAN
from sklearn.datasets import make_moons

# Create non-spherical data (two crescent moons)
X, y = make_moons(n_samples=300, noise=0.05, random_state=42)

# K-Means fails on this data (tries to find spherical clusters)
kmeans = KMeans(n_clusters=2, random_state=42)
kmeans_labels = kmeans.fit_predict(X)

# DBSCAN handles it perfectly
dbscan = DBSCAN(eps=0.2, min_samples=5)
dbscan_labels = dbscan.fit_predict(X)

fig, axes = plt.subplots(1, 2, figsize=(16, 6))

axes[0].scatter(X[:, 0], X[:, 1], c=kmeans_labels, cmap='viridis', s=50)
axes[0].set_title("K-Means (Fails!)")

axes[1].scatter(X[:, 0], X[:, 1], c=dbscan_labels, cmap='viridis', s=50)
axes[1].set_title("DBSCAN (Works!)")

plt.show()
```

### Comparison: K-Means vs Hierarchical vs DBSCAN

| Feature | K-Means | Hierarchical | DBSCAN |
|---------|---------|-------------|--------|
| Specify K? | Yes | Yes (or cut dendrogram) | No |
| Cluster Shape | Spherical | Any | Any |
| Handles Outliers? | No | No | Yes |
| Scalability | Fast (large datasets) | Slow | Medium |
| Handles Noise? | No | No | Yes |

### 🎯 Day 18 Practice

- Apply all three clustering methods to the same dataset and compare.
- Create crescent-shaped data and show that DBSCAN outperforms K-Means.
- Plot a dendrogram and determine the optimal number of clusters.

---

## Day 19: Dimensionality Reduction — PCA

### The Curse of Dimensionality

As the number of features increases:
- The data becomes **sparse** (points are far apart).
- Models need **exponentially more** data to learn.
- **Overfitting** becomes more likely.
- **Visualization** becomes impossible (can't plot 100 dimensions!).

### What is PCA?

**Principal Component Analysis (PCA)** reduces the number of features while keeping as much **variance** (information) as possible.

**Analogy:** Imagine taking a photo of a 3D object. The photo is a 2D projection that still captures most of the object's shape. PCA does the same thing — projects high-dimensional data into fewer dimensions.

### How PCA Works

1. **Center** the data (subtract the mean).
2. Compute the **covariance matrix** (how features vary together).
3. Find the **eigenvectors** (directions of maximum variance) and **eigenvalues** (amount of variance in each direction).
4. Sort eigenvectors by eigenvalues (most variance first).
5. **Project** the data onto the top K eigenvectors.

### Implementation

```python
from sklearn.decomposition import PCA
from sklearn.datasets import load_iris
import matplotlib.pyplot as plt

# Load data (4 features)
iris = load_iris()
X = iris.data
y = iris.target

# Reduce from 4 dimensions to 2
pca = PCA(n_components=2)
X_pca = pca.fit_transform(X)

# How much variance is explained?
print(f"Explained variance ratio: {pca.explained_variance_ratio_}")
print(f"Total variance explained: {sum(pca.explained_variance_ratio_):.2%}")

# Visualize
plt.figure(figsize=(10, 7))
scatter = plt.scatter(X_pca[:, 0], X_pca[:, 1], c=y, cmap='viridis',
                       alpha=0.8, s=50, edgecolors='black')
plt.colorbar(scatter, label='Species')
plt.title("PCA — Iris Dataset (4D → 2D)", fontsize=16)
plt.xlabel(f"PC1 ({pca.explained_variance_ratio_[0]:.1%} variance)")
plt.ylabel(f"PC2 ({pca.explained_variance_ratio_[1]:.1%} variance)")
plt.show()
```

### Choosing the Number of Components

```python
# Fit PCA with all components
pca_full = PCA()
pca_full.fit(X)

# Cumulative explained variance
cumulative_variance = np.cumsum(pca_full.explained_variance_ratio_)

plt.figure(figsize=(10, 6))
plt.plot(range(1, len(cumulative_variance) + 1), cumulative_variance, 'bo-')
plt.axhline(y=0.95, color='r', linestyle='--', label='95% variance')
plt.title("Cumulative Explained Variance")
plt.xlabel("Number of Components")
plt.ylabel("Cumulative Explained Variance")
plt.legend()
plt.grid(True, alpha=0.3)
plt.show()

# Choose enough components to explain 95% of variance
pca_95 = PCA(n_components=0.95)  # Automatically selects components
X_reduced = pca_95.fit_transform(X)
print(f"Components needed for 95% variance: {pca_95.n_components_}")
```

### When to Use PCA

| Use Case | Example |
|----------|---------|
| **Visualization** | Reduce 100 features to 2-3 for plotting |
| **Speed up training** | Reduce features before feeding into a slow algorithm |
| **Remove noise** | Minor components often capture noise, not signal |
| **Remove multicollinearity** | PCA components are uncorrelated by definition |

### 🎯 Day 19 Practice

- Apply PCA to a high-dimensional dataset and visualize in 2D.
- Plot the cumulative explained variance and choose the optimal number of components.
- Compare model accuracy with and without PCA.

---

## Day 20: Pipelines & Complete ML Workflow

### What is a Pipeline?

A Pipeline chains together multiple preprocessing steps and a model into a **single object**. This ensures:
- All steps are applied consistently.
- No data leakage (fit on training data only).
- Clean, reproducible code.

### Building a Pipeline

```python
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.decomposition import PCA
from sklearn.ensemble import RandomForestClassifier

# Create a pipeline
pipe = Pipeline([
    ('scaler', StandardScaler()),         # Step 1: Scale features
    ('pca', PCA(n_components=10)),        # Step 2: Reduce dimensions
    ('classifier', RandomForestClassifier(n_estimators=100, random_state=42))  # Step 3: Classify
])

# Train the entire pipeline
pipe.fit(X_train, y_train)

# Predict (all preprocessing is applied automatically)
y_pred = pipe.predict(X_test)

# Evaluate
print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")
```

### Column Transformer (Different Preprocessing for Different Columns)

```python
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import StandardScaler, OneHotEncoder
from sklearn.impute import SimpleImputer

# Define preprocessing for different column types
numeric_features = ['Age', 'Salary', 'Experience']
categorical_features = ['Department', 'Education']

preprocessor = ColumnTransformer(
    transformers=[
        ('num', Pipeline([
            ('imputer', SimpleImputer(strategy='median')),
            ('scaler', StandardScaler())
        ]), numeric_features),
        ('cat', Pipeline([
            ('imputer', SimpleImputer(strategy='most_frequent')),
            ('encoder', OneHotEncoder(handle_unknown='ignore'))
        ]), categorical_features)
    ]
)

# Full pipeline
full_pipeline = Pipeline([
    ('preprocessor', preprocessor),
    ('classifier', RandomForestClassifier(n_estimators=100, random_state=42))
])

full_pipeline.fit(X_train, y_train)
print(f"Accuracy: {full_pipeline.score(X_test, y_test):.4f}")
```

### Pipeline with Grid Search

```python
from sklearn.model_selection import GridSearchCV

# Note: use double underscore to access nested parameters
param_grid = {
    'pca__n_components': [5, 10, 15],
    'classifier__n_estimators': [50, 100, 200],
    'classifier__max_depth': [3, 5, None]
}

grid_search = GridSearchCV(pipe, param_grid, cv=5, scoring='accuracy', n_jobs=-1)
grid_search.fit(X_train, y_train)

print(f"Best params: {grid_search.best_params_}")
print(f"Best score: {grid_search.best_score_:.4f}")
```

### Complete ML Project Template

```python
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split, cross_val_score, GridSearchCV
from sklearn.pipeline import Pipeline
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import StandardScaler, OneHotEncoder
from sklearn.impute import SimpleImputer
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report
import warnings
warnings.filterwarnings('ignore')

# ═══════════════════════════════════════════════════════════════
# 1. LOAD DATA
# ═══════════════════════════════════════════════════════════════
df = pd.read_csv("your_dataset.csv")

# ═══════════════════════════════════════════════════════════════
# 2. EXPLORE DATA
# ═══════════════════════════════════════════════════════════════
print(df.shape)
print(df.info())
print(df.describe())
print(df.isnull().sum())

# ═══════════════════════════════════════════════════════════════
# 3. PREPARE DATA
# ═══════════════════════════════════════════════════════════════
X = df.drop("target", axis=1)
y = df["target"]
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# ═══════════════════════════════════════════════════════════════
# 4. BUILD PIPELINE
# ═══════════════════════════════════════════════════════════════
numeric_features = X.select_dtypes(include=['int64', 'float64']).columns.tolist()
categorical_features = X.select_dtypes(include=['object']).columns.tolist()

preprocessor = ColumnTransformer([
    ('num', Pipeline([
        ('imputer', SimpleImputer(strategy='median')),
        ('scaler', StandardScaler())
    ]), numeric_features),
    ('cat', Pipeline([
        ('imputer', SimpleImputer(strategy='most_frequent')),
        ('encoder', OneHotEncoder(handle_unknown='ignore'))
    ]), categorical_features)
])

# ═══════════════════════════════════════════════════════════════
# 5. COMPARE MODELS
# ═══════════════════════════════════════════════════════════════
models = {
    "Logistic Regression": LogisticRegression(max_iter=10000),
    "Random Forest": RandomForestClassifier(n_estimators=100, random_state=42),
    "Gradient Boosting": GradientBoostingClassifier(n_estimators=100, random_state=42)
}

for name, model in models.items():
    pipe = Pipeline([('preprocessor', preprocessor), ('classifier', model)])
    scores = cross_val_score(pipe, X_train, y_train, cv=5, scoring='accuracy')
    print(f"{name:25s} → Accuracy: {scores.mean():.4f} ± {scores.std():.4f}")

# ═══════════════════════════════════════════════════════════════
# 6. TUNE BEST MODEL
# ═══════════════════════════════════════════════════════════════
best_pipe = Pipeline([
    ('preprocessor', preprocessor),
    ('classifier', RandomForestClassifier(random_state=42))
])

param_grid = {
    'classifier__n_estimators': [100, 200, 300],
    'classifier__max_depth': [5, 10, None],
}

grid = GridSearchCV(best_pipe, param_grid, cv=5, scoring='accuracy', n_jobs=-1)
grid.fit(X_train, y_train)

# ═══════════════════════════════════════════════════════════════
# 7. FINAL EVALUATION
# ═══════════════════════════════════════════════════════════════
print(f"\nBest Parameters: {grid.best_params_}")
print(f"Best CV Score: {grid.best_score_:.4f}")
print(f"Test Score: {grid.score(X_test, y_test):.4f}")
print(f"\n{classification_report(y_test, grid.predict(X_test))}")
```

### 🎯 Day 20 Practice

- Build a complete pipeline with preprocessing, scaling, and classification.
- Use ColumnTransformer to handle mixed data types.
- Compare at least 3 models using cross-validation within a pipeline.

---

## Day 21: Review & Mini-Project — Putting It All Together

### Week 3 Review Checklist

- [ ] Can you explain feature engineering and name 5 techniques?
- [ ] Do you understand Ridge vs Lasso vs Elastic Net?
- [ ] Can you explain K-Means, Hierarchical Clustering, and DBSCAN?
- [ ] Do you understand PCA and when to use it?
- [ ] Can you build a complete ML pipeline?

### Mini-Project: Customer Segmentation

Build a complete unsupervised learning project:

1. **Load** a customer dataset (e.g., Mall Customers from Kaggle).
2. **Explore** the data (statistics, distributions, correlations).
3. **Preprocess** (handle missing values, scale features).
4. **Apply PCA** to reduce dimensions.
5. **Cluster** using K-Means and DBSCAN.
6. **Evaluate** using the Elbow Method and Silhouette Score.
7. **Visualize** the clusters.
8. **Interpret** each cluster (What type of customer is in each group?).

### 🎯 Day 21 Practice

- Complete the mini-project above.
- Write a brief report summarizing your findings.
- Try at least 2 different clustering algorithms and compare.

---

---

# 🗓️ WEEK 4: Deep Learning & Neural Networks

---

## Day 22: Introduction to Neural Networks

### What is a Neural Network?

A Neural Network is a computing system inspired by the **biological neural networks** in the brain. It consists of layers of interconnected "neurons" that process information.

### The Perceptron (Single Neuron)

The simplest neural network is a single neuron, called a **perceptron**:

```
Inputs        Weights         Sum + Activation    Output
  x₁ ─── w₁ ──╲
  x₂ ─── w₂ ───→ Σ(wᵢxᵢ + b) → f(z) → ŷ
  x₃ ─── w₃ ──╱
               + b (bias)
```

**Steps:**
1. Multiply each input by its weight.
2. Sum all the products and add the bias.
3. Pass through an **activation function**.
4. Output the result.

**This is just linear regression + an activation function!**

### Activation Functions

Activation functions introduce **non-linearity**, allowing the network to learn complex patterns.

| Function | Formula | Range | When to Use |
|----------|---------|-------|-------------|
| **Sigmoid** | 1/(1+e⁻ˣ) | (0, 1) | Output layer for binary classification |
| **Tanh** | (eˣ-e⁻ˣ)/(eˣ+e⁻ˣ) | (-1, 1) | Hidden layers (centered around 0) |
| **ReLU** | max(0, x) | [0, ∞) | Most common for hidden layers |
| **Softmax** | eˣⁱ/Σeˣʲ | (0, 1), sums to 1 | Output layer for multi-class classification |

```python
import numpy as np
import matplotlib.pyplot as plt

x = np.linspace(-5, 5, 100)

fig, axes = plt.subplots(1, 4, figsize=(20, 4))

# Sigmoid
axes[0].plot(x, 1/(1+np.exp(-x)), linewidth=2)
axes[0].set_title("Sigmoid")

# Tanh
axes[1].plot(x, np.tanh(x), linewidth=2, color='orange')
axes[1].set_title("Tanh")

# ReLU
axes[2].plot(x, np.maximum(0, x), linewidth=2, color='green')
axes[2].set_title("ReLU")

# Leaky ReLU
axes[3].plot(x, np.where(x > 0, x, 0.01*x), linewidth=2, color='red')
axes[3].set_title("Leaky ReLU")

for ax in axes:
    ax.grid(True, alpha=0.3)
    ax.axhline(y=0, color='k', linewidth=0.5)
    ax.axvline(x=0, color='k', linewidth=0.5)

plt.tight_layout()
plt.show()
```

### Multi-Layer Neural Network

A neural network with **hidden layers** can learn complex, non-linear patterns.

```
Input Layer      Hidden Layer 1    Hidden Layer 2    Output Layer
   ○                ○                 ○
   ○ ──────────── ○ ───────────── ○ ──────────── ○
   ○                ○                 ○
   ○ ──────────── ○ ───────────── ○
                    ○                 ○

(features)      (learned          (more complex      (prediction)
                 patterns)         patterns)
```

**Why hidden layers matter:**
- 0 hidden layers = linear model (can only learn straight-line boundaries).
- 1 hidden layer = can approximate any continuous function (Universal Approximation Theorem).
- More hidden layers = can learn hierarchical features (deep learning).

### Forward Propagation

Data flows **forward** through the network:

```
Input → Layer 1 → Layer 2 → ... → Output

At each layer:
z = W·x + b        (linear transformation)
a = f(z)            (activation function)
```

### Backpropagation

How the network **learns**:

1. **Forward pass:** Compute the prediction.
2. **Compute loss:** How far off is the prediction?
3. **Backward pass:** Compute the gradient of the loss with respect to each weight (using the **chain rule** of calculus).
4. **Update weights:** Adjust weights to reduce the loss.

```
prediction = forward_pass(inputs)
loss = compute_loss(prediction, actual)
gradients = backward_pass(loss)
weights = weights - learning_rate × gradients
```

### Implementation with TensorFlow/Keras

```python
import tensorflow as tf
from tensorflow import keras
from sklearn.datasets import load_iris
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

# Load data
iris = load_iris()
X_train, X_test, y_train, y_test = train_test_split(
    iris.data, iris.target, test_size=0.2, random_state=42
)

# Scale
scaler = StandardScaler()
X_train = scaler.fit_transform(X_train)
X_test = scaler.transform(X_test)

# Build the neural network
model = keras.Sequential([
    keras.layers.Dense(64, activation='relu', input_shape=(4,)),   # Hidden layer 1
    keras.layers.Dense(32, activation='relu'),                      # Hidden layer 2
    keras.layers.Dense(3, activation='softmax')                     # Output layer (3 classes)
])

# Compile
model.compile(
    optimizer='adam',
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)

# Summary
model.summary()

# Train
history = model.fit(X_train, y_train, epochs=100, batch_size=16,
                     validation_split=0.2, verbose=1)

# Evaluate
loss, accuracy = model.evaluate(X_test, y_test)
print(f"Test Accuracy: {accuracy:.4f}")
```

### Plotting Training History

```python
plt.figure(figsize=(14, 5))

# Loss plot
plt.subplot(1, 2, 1)
plt.plot(history.history['loss'], label='Training Loss')
plt.plot(history.history['val_loss'], label='Validation Loss')
plt.title("Loss over Epochs")
plt.xlabel("Epoch")
plt.ylabel("Loss")
plt.legend()

# Accuracy plot
plt.subplot(1, 2, 2)
plt.plot(history.history['accuracy'], label='Training Accuracy')
plt.plot(history.history['val_accuracy'], label='Validation Accuracy')
plt.title("Accuracy over Epochs")
plt.xlabel("Epoch")
plt.ylabel("Accuracy")
plt.legend()

plt.tight_layout()
plt.show()
```

### Key Terminology

| Term | Definition |
|------|-----------|
| **Epoch** | One complete pass through the entire training dataset |
| **Batch** | A subset of training data used in one gradient update |
| **Batch Size** | Number of samples in one batch |
| **Learning Rate** | Step size for weight updates |
| **Optimizer** | Algorithm that updates weights (Adam, SGD, RMSprop) |
| **Loss Function** | Measures how wrong the predictions are |

### 🎯 Day 22 Practice

- Build a neural network with Keras for classification.
- Experiment with different numbers of layers and neurons.
- Plot the training and validation loss/accuracy curves.

---

## Day 23: Deep Learning — Training Techniques

### Common Problems in Training Neural Networks

#### 1. Vanishing Gradients

In deep networks, gradients can become **extremely small** as they propagate backward, causing early layers to learn very slowly.

**Solutions:**
- Use **ReLU** activation (instead of sigmoid/tanh).
- Use **batch normalization**.
- Use **skip connections** (ResNet architecture).

#### 2. Overfitting

Neural networks have millions of parameters and can easily memorize training data.

**Solutions:**

##### Dropout
Randomly "turns off" a percentage of neurons during training, forcing the network to not rely on any single neuron.

```python
model = keras.Sequential([
    keras.layers.Dense(128, activation='relu'),
    keras.layers.Dropout(0.3),       # Drop 30% of neurons randomly
    keras.layers.Dense(64, activation='relu'),
    keras.layers.Dropout(0.3),
    keras.layers.Dense(10, activation='softmax')
])
```

##### Early Stopping
Stop training when validation loss stops improving.

```python
early_stop = keras.callbacks.EarlyStopping(
    monitor='val_loss',
    patience=10,         # Wait 10 epochs for improvement
    restore_best_weights=True
)

history = model.fit(X_train, y_train, epochs=500,
                     validation_split=0.2, callbacks=[early_stop])
```

##### Batch Normalization
Normalizes the inputs of each layer, stabilizing and accelerating training.

```python
model = keras.Sequential([
    keras.layers.Dense(128, activation='relu'),
    keras.layers.BatchNormalization(),
    keras.layers.Dense(64, activation='relu'),
    keras.layers.BatchNormalization(),
    keras.layers.Dense(10, activation='softmax')
])
```

### Optimizers

| Optimizer | Description | When to Use |
|-----------|-------------|-------------|
| **SGD** | Basic gradient descent | Baseline, needs careful learning rate tuning |
| **Adam** | Adaptive learning rate per parameter | Default choice for most problems |
| **RMSprop** | Good for non-stationary problems | Recurrent neural networks |
| **AdaGrad** | Good for sparse data | NLP, recommender systems |

### Learning Rate Scheduling

```python
# Reduce learning rate when validation loss plateaus
lr_scheduler = keras.callbacks.ReduceLROnPlateau(
    monitor='val_loss',
    factor=0.5,          # Multiply LR by 0.5
    patience=5,          # Wait 5 epochs
    min_lr=1e-7
)

history = model.fit(X_train, y_train, epochs=200,
                     validation_split=0.2,
                     callbacks=[early_stop, lr_scheduler])
```

### Data Augmentation (for Image Data)

Creates new training examples by applying random transformations.

```python
from tensorflow.keras.preprocessing.image import ImageDataGenerator

datagen = ImageDataGenerator(
    rotation_range=20,
    width_shift_range=0.2,
    height_shift_range=0.2,
    horizontal_flip=True,
    zoom_range=0.2
)
```

### 🎯 Day 23 Practice

- Train a neural network with and without Dropout. Compare overfitting.
- Implement Early Stopping and observe when training stops.
- Try different optimizers (SGD, Adam) and compare convergence speed.

---

## Day 24: Convolutional Neural Networks (CNN) — Image Recognition

### What is a CNN?

A **Convolutional Neural Network (CNN)** is a type of neural network designed specifically for processing **images** (and other grid-like data).

### Why Not Use Regular Neural Networks for Images?

A 224×224 color image has 224 × 224 × 3 = **150,528 pixels**. A regular (fully connected) neural network would need one weight for each pixel for each neuron — resulting in **millions of parameters**, leading to overfitting and slow training.

CNNs solve this using **convolutions** — they scan the image with small filters, dramatically reducing parameters while learning spatial patterns.

### How Convolution Works

A **filter** (or kernel) is a small matrix (e.g., 3×3) that slides across the image, performing element-wise multiplication and summing the results.

```
Image Patch:     Filter:        Output:
[1, 2, 3]     [1, 0, -1]
[4, 5, 6]  ×  [1, 0, -1]  =  1×1 + 2×0 + 3×(-1) + 4×1 + 5×0 + 6×(-1) + 7×1 + 8×0 + 9×(-1) = -6
[7, 8, 9]     [1, 0, -1]

The filter slides across the entire image, producing a "feature map"
```

**What do filters detect?**
- Early layers: edges, corners, textures
- Middle layers: shapes, patterns
- Deep layers: objects, faces, complex structures

### CNN Architecture

```
Input Image → [Conv → ReLU → Pool] × N → Flatten → Dense → Output

Conv:    Extract features using filters
ReLU:    Add non-linearity
Pool:    Reduce spatial dimensions (downsample)
Flatten: Convert 2D feature maps to 1D vector
Dense:   Make final prediction
```

### Pooling (Downsampling)

**Max Pooling** takes the maximum value from each patch:

```
[1, 3, 2, 1]           [3, 2]
[4, 6, 5, 2]   →2×2→   [8, 7]
[7, 8, 1, 3]    max
[2, 5, 4, 7]    pool
```

This makes the network:
- **Invariant to small translations** (the cat can be slightly shifted).
- **More efficient** (reduces computation).

### Implementation with Keras

```python
import tensorflow as tf
from tensorflow import keras

# Build CNN
model = keras.Sequential([
    # First Convolutional Block
    keras.layers.Conv2D(32, (3, 3), activation='relu', input_shape=(28, 28, 1)),
    keras.layers.MaxPooling2D((2, 2)),

    # Second Convolutional Block
    keras.layers.Conv2D(64, (3, 3), activation='relu'),
    keras.layers.MaxPooling2D((2, 2)),

    # Third Convolutional Block
    keras.layers.Conv2D(64, (3, 3), activation='relu'),

    # Classification Head
    keras.layers.Flatten(),
    keras.layers.Dense(64, activation='relu'),
    keras.layers.Dropout(0.5),
    keras.layers.Dense(10, activation='softmax')  # 10 classes (digits 0-9)
])

model.summary()

# Compile
model.compile(
    optimizer='adam',
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)

# Load MNIST dataset (handwritten digits)
(X_train, y_train), (X_test, y_test) = keras.datasets.mnist.load_data()

# Preprocess
X_train = X_train.reshape(-1, 28, 28, 1).astype('float32') / 255.0
X_test = X_test.reshape(-1, 28, 28, 1).astype('float32') / 255.0

# Train
history = model.fit(X_train, y_train, epochs=10, batch_size=64,
                     validation_split=0.1)

# Evaluate
loss, accuracy = model.evaluate(X_test, y_test)
print(f"Test Accuracy: {accuracy:.4f}")
```

### Key CNN Hyperparameters

| Parameter | Typical Values | Effect |
|-----------|---------------|--------|
| **Number of filters** | 32, 64, 128, 256 | More filters = more features detected |
| **Filter size** | 3×3, 5×5 | Larger = captures bigger patterns |
| **Stride** | 1, 2 | Step size when sliding the filter |
| **Padding** | 'same', 'valid' | Whether to pad edges with zeros |
| **Pool size** | 2×2 | Size of the pooling window |

### Famous CNN Architectures

| Architecture | Year | Key Innovation |
|-------------|------|----------------|
| **LeNet-5** | 1998 | First successful CNN (handwriting recognition) |
| **AlexNet** | 2012 | Popularized deep learning, won ImageNet |
| **VGGNet** | 2014 | Very deep (16-19 layers), small 3×3 filters |
| **GoogLeNet** | 2014 | Inception modules (parallel filters of different sizes) |
| **ResNet** | 2015 | Skip connections (solved vanishing gradients for 150+ layers) |

### 🎯 Day 24 Practice

- Build a CNN to classify MNIST digits. Aim for >99% accuracy.
- Visualize some predictions (show the image and the predicted digit).
- Experiment with different architectures (more layers, different filter sizes).

---

## Day 25: Transfer Learning — Stand on the Shoulders of Giants

### What is Transfer Learning?

Instead of training a model from scratch, **transfer learning** uses a **pre-trained model** (trained on millions of images) and adapts it to your specific task.

**Analogy:** You don't learn to see from scratch every time you encounter a new object. Your brain reuses its existing knowledge of shapes, edges, and textures. Transfer learning does the same thing.

### Why Transfer Learning?

1. **Less data needed** — the pre-trained model already knows general features.
2. **Faster training** — you only need to fine-tune the last few layers.
3. **Better performance** — pre-trained models have learned from millions of images.

### How It Works

```
Pre-trained Model (e.g., ResNet trained on ImageNet):
┌──────────────────────────────────────────────┐
│ Conv layers → detect edges, textures, shapes │ ← FREEZE these (keep learned features)
│ Deep layers → detect complex patterns        │ ← FREEZE these
│ Final layers → classify 1000 ImageNet classes │ ← REPLACE with your custom classifier
└──────────────────────────────────────────────┘
```

### Implementation

```python
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras.applications import MobileNetV2
from tensorflow.keras.layers import Dense, GlobalAveragePooling2D, Dropout

# 1. Load pre-trained model (without the top classification layer)
base_model = MobileNetV2(
    weights='imagenet',         # Pre-trained on ImageNet
    include_top=False,          # Remove the original classifier
    input_shape=(224, 224, 3)
)

# 2. Freeze the base model (don't train its weights)
base_model.trainable = False

# 3. Add custom classification layers
model = keras.Sequential([
    base_model,
    GlobalAveragePooling2D(),
    Dense(128, activation='relu'),
    Dropout(0.5),
    Dense(5, activation='softmax')  # 5 classes for your task
])

# 4. Compile
model.compile(
    optimizer='adam',
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)

model.summary()
# Total params: ~2.3M
# Trainable params: ~66K (only your custom layers!)
# Non-trainable params: ~2.3M (frozen base model)
```

### Fine-Tuning

After initial training, you can **unfreeze** some of the base model's layers and train them with a very low learning rate:

```python
# Unfreeze the last 20 layers of the base model
base_model.trainable = True
for layer in base_model.layers[:-20]:
    layer.trainable = False

# Re-compile with a much lower learning rate
model.compile(
    optimizer=keras.optimizers.Adam(learning_rate=1e-5),  # Very low LR!
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)

# Continue training
model.fit(X_train, y_train, epochs=10, validation_split=0.2)
```

### Popular Pre-trained Models

| Model | Parameters | Top-1 Accuracy (ImageNet) | Speed |
|-------|-----------|--------------------------|-------|
| MobileNetV2 | 3.4M | 71.3% | Very Fast |
| ResNet50 | 25.6M | 76.0% | Medium |
| VGG16 | 138M | 71.3% | Slow |
| EfficientNetB0 | 5.3M | 77.1% | Fast |
| InceptionV3 | 23.8M | 77.9% | Medium |

### 🎯 Day 25 Practice

- Use MobileNetV2 to classify a custom image dataset.
- Compare training from scratch vs transfer learning (accuracy and training time).
- Experiment with fine-tuning different numbers of layers.

---

## Day 26: Natural Language Processing (NLP) Basics

### What is NLP?

**Natural Language Processing (NLP)** is the field of AI that deals with **human language** — text and speech. ML models don't understand words; they need numbers.

### Text Preprocessing Pipeline

```
Raw Text → Lowercase → Remove Punctuation → Tokenize → Remove Stop Words → Stem/Lemmatize → Vectorize
```

#### 1. Tokenization

Splitting text into individual words or tokens.

```python
from nltk.tokenize import word_tokenize
import nltk
nltk.download('punkt')

text = "Machine learning is amazing! Let's learn it."
tokens = word_tokenize(text)
print(tokens)  # ['Machine', 'learning', 'is', 'amazing', '!', 'Let', "'s", 'learn', 'it', '.']
```

#### 2. Stop Words Removal

Remove common words that don't carry much meaning (the, is, at, etc.).

```python
from nltk.corpus import stopwords
nltk.download('stopwords')

stop_words = set(stopwords.words('english'))
filtered = [word for word in tokens if word.lower() not in stop_words]
print(filtered)  # ['Machine', 'learning', 'amazing', '!', 'Let', "'s", 'learn', '.']
```

#### 3. Stemming and Lemmatization

Reduce words to their root form.

```python
from nltk.stem import PorterStemmer, WordNetLemmatizer

stemmer = PorterStemmer()
lemmatizer = WordNetLemmatizer()

words = ["running", "runs", "ran", "better", "studying"]

# Stemming (crude, rule-based)
print([stemmer.stem(w) for w in words])     # ['run', 'run', 'ran', 'better', 'studi']

# Lemmatization (uses dictionary, more accurate)
print([lemmatizer.lemmatize(w, pos='v') for w in words])  # ['run', 'run', 'run', 'better', 'study']
```

### Text Vectorization (Converting Text to Numbers)

#### Bag of Words (BoW)

Counts how many times each word appears.

```python
from sklearn.feature_extraction.text import CountVectorizer

documents = [
    "I love machine learning",
    "Machine learning is great",
    "I love deep learning"
]

vectorizer = CountVectorizer()
X = vectorizer.fit_transform(documents)

print(vectorizer.get_feature_names_out())
# ['deep', 'great', 'is', 'learning', 'love', 'machine']
print(X.toarray())
# [[0, 0, 0, 1, 1, 1],    "I love machine learning"
#  [0, 1, 1, 1, 0, 1],    "Machine learning is great"
#  [1, 0, 0, 1, 1, 0]]    "I love deep learning"
```

#### TF-IDF (Term Frequency–Inverse Document Frequency)

Like BoW, but weights words by how **unique** they are across documents. Common words get lower weights.

```python
from sklearn.feature_extraction.text import TfidfVectorizer

tfidf = TfidfVectorizer()
X_tfidf = tfidf.fit_transform(documents)
print(X_tfidf.toarray())
```

### Sentiment Analysis Example

```python
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score

# Sample data
reviews = [
    "This movie is great!", "Absolutely loved it", "Best movie ever",
    "Terrible movie", "Worst film I've seen", "Complete waste of time",
    "Amazing storyline", "Boring and predictable", "Highly recommend",
    "Don't waste your money"
]
labels = [1, 1, 1, 0, 0, 0, 1, 0, 1, 0]  # 1 = positive, 0 = negative

# Vectorize
tfidf = TfidfVectorizer()
X = tfidf.fit_transform(reviews)

# Split
X_train, X_test, y_train, y_test = train_test_split(X, labels, test_size=0.3, random_state=42)

# Train
model = LogisticRegression()
model.fit(X_train, y_train)

# Predict
y_pred = model.predict(X_test)
print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")

# Test on new reviews
new_reviews = ["This film was excellent!", "I hated every minute"]
new_X = tfidf.transform(new_reviews)
predictions = model.predict(new_X)
for review, pred in zip(new_reviews, predictions):
    sentiment = "Positive" if pred == 1 else "Negative"
    print(f'"{review}" → {sentiment}')
```

### Word Embeddings (Word2Vec, GloVe)

Instead of sparse vectors (BoW, TF-IDF), word embeddings represent words as **dense vectors** where similar words are close together in vector space.

```
king - man + woman ≈ queen
paris - france + italy ≈ rome
```

### 🎯 Day 26 Practice

- Preprocess a text dataset (tokenize, remove stop words, vectorize).
- Build a sentiment classifier using TF-IDF + Logistic Regression.
- Compare BoW vs TF-IDF for text classification accuracy.

---

## Day 27: Recurrent Neural Networks (RNN) & LSTMs

### What are RNNs?

**Recurrent Neural Networks (RNNs)** are designed for **sequential data** — data where **order matters** (text, time series, speech, music).

Unlike regular neural networks that process each input independently, RNNs have a **memory** — they pass information from one step to the next.

### How RNNs Work

```
         ┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐
Input:   │ "I" │→ │"love"│→ │ "ML" │→ │ "!" │
         └──┬──┘   └──┬──┘   └──┬──┘   └──┬──┘
            ↓         ↓         ↓         ↓
Hidden:  [h₀]  →  [h₁]  →  [h₂]  →  [h₃]  → Output
            ↑         ↑         ↑         ↑
            └─────────┘         └─────────┘
            (hidden state carries memory forward)
```

At each time step:
```
hₜ = f(W_hh × hₜ₋₁ + W_xh × xₜ + b)
```

### The Problem: Vanishing Gradients in RNNs

Simple RNNs struggle with **long-term dependencies**. In the sentence "I was born in France and I speak ___", the RNN might forget "France" by the time it needs it.

### LSTM (Long Short-Term Memory)

LSTMs solve this by adding a **cell state** (like a conveyor belt) that can carry information across long sequences, plus **gates** that control what information to keep, forget, or add.

**Three Gates:**
1. **Forget Gate:** Decides what information to throw away from the cell state.
2. **Input Gate:** Decides what new information to add to the cell state.
3. **Output Gate:** Decides what to output based on the cell state.

```
       ┌────────────────── Cell State (conveyor belt) ──────────────────┐
       │                                                                 │
       ↓    Forget Gate    Input Gate      Output Gate                   ↓
  [Previous] → [σ] → [×] → [+] → ─────────────── → [×] → [New Hidden]
   Cell State        ↑                                ↑
                  [σ × tanh]                         [σ × tanh]
                     ↑                                ↑
               [Current Input + Previous Hidden State]
```

### Implementation

```python
import tensorflow as tf
from tensorflow import keras
import numpy as np

# Example: Predict next value in a time series

# Generate sine wave data
time = np.arange(0, 100, 0.1)
data = np.sin(time)

# Create sequences
def create_sequences(data, seq_length):
    X, y = [], []
    for i in range(len(data) - seq_length):
        X.append(data[i:i+seq_length])
        y.append(data[i+seq_length])
    return np.array(X), np.array(y)

seq_length = 50
X, y = create_sequences(data, seq_length)
X = X.reshape(-1, seq_length, 1)  # (samples, time steps, features)

# Split
split = int(0.8 * len(X))
X_train, X_test = X[:split], X[split:]
y_train, y_test = y[:split], y[split:]

# Build LSTM model
model = keras.Sequential([
    keras.layers.LSTM(50, return_sequences=True, input_shape=(seq_length, 1)),
    keras.layers.LSTM(50),
    keras.layers.Dense(1)
])

model.compile(optimizer='adam', loss='mse')

# Train
history = model.fit(X_train, y_train, epochs=20, batch_size=32,
                     validation_split=0.1, verbose=1)

# Predict
y_pred = model.predict(X_test)

# Visualize
import matplotlib.pyplot as plt
plt.figure(figsize=(14, 5))
plt.plot(y_test, label='Actual')
plt.plot(y_pred, label='Predicted')
plt.title("LSTM — Sine Wave Prediction")
plt.legend()
plt.show()
```

### GRU (Gated Recurrent Unit)

A simplified version of LSTM with **fewer parameters** (2 gates instead of 3). Often performs similarly with faster training.

```python
model = keras.Sequential([
    keras.layers.GRU(50, return_sequences=True, input_shape=(seq_length, 1)),
    keras.layers.GRU(50),
    keras.layers.Dense(1)
])
```

### When to Use What

| Architecture | Best For |
|-------------|---------|
| **Simple RNN** | Very short sequences |
| **LSTM** | Long sequences with long-term dependencies |
| **GRU** | Similar to LSTM but faster (fewer parameters) |
| **Transformer** | State-of-the-art for NLP (Day 28) |

### 🎯 Day 27 Practice

- Build an LSTM to predict a time series (sine wave or stock prices).
- Compare Simple RNN vs LSTM vs GRU on the same task.
- Try text generation: train an LSTM on a text corpus and generate new text.

---

## Day 28: Introduction to Transformers & Modern ML

### The Transformer Revolution

Transformers, introduced in the 2017 paper "Attention is All You Need," have **revolutionized** machine learning. They power:
- **GPT** (ChatGPT, GPT-4) — text generation
- **BERT** — text understanding
- **Vision Transformers (ViT)** — image classification
- **DALL-E** — image generation
- **Whisper** — speech recognition

### The Problem with RNNs

RNNs process sequences **one step at a time** (sequentially). This is:
- **Slow** — can't be parallelized.
- **Forgetful** — still struggles with very long sequences despite LSTM.

### The Attention Mechanism

**Core idea:** Instead of processing sequentially, let the model **look at ALL parts of the input simultaneously** and decide which parts are most relevant.

**Analogy:** When you read "The animal didn't cross the road because **it** was too tired," you instantly know "it" refers to "animal" (not "road"). The attention mechanism does this by computing relevance scores between every pair of words.

### Self-Attention (Simplified)

For each word, compute three vectors:
- **Query (Q):** "What am I looking for?"
- **Key (K):** "What do I contain?"
- **Value (V):** "What information can I provide?"

```
Attention(Q, K, V) = softmax(Q × K^T / √d) × V

Where:
  Q × K^T  = relevance scores (how much each word relates to every other word)
  √d       = scaling factor (prevents large values)
  softmax  = normalizes scores to probabilities
  × V      = weighted combination of values
```

### Transformer Architecture

```
Input → [Embedding + Positional Encoding] → [Multi-Head Attention + Feed Forward] × N → Output

Encoder (understands input):
  - Multi-Head Self-Attention → sees relationships between all input words
  - Feed-Forward Network → processes each position independently
  - Layer Normalization + Residual Connections

Decoder (generates output):
  - Masked Multi-Head Self-Attention → can only see previous words
  - Cross-Attention → attends to encoder output
  - Feed-Forward Network
```

### Using Pre-trained Transformers with Hugging Face

```python
# Install: pip install transformers

from transformers import pipeline

# Sentiment Analysis
sentiment = pipeline("sentiment-analysis")
result = sentiment("I absolutely love machine learning! It's fascinating.")
print(result)  # [{'label': 'POSITIVE', 'score': 0.9998}]

# Text Generation
generator = pipeline("text-generation", model="gpt2")
result = generator("Machine learning is", max_length=50)
print(result[0]['generated_text'])

# Question Answering
qa = pipeline("question-answering")
result = qa(
    question="What is machine learning?",
    context="Machine learning is a subset of AI that allows computers to learn from data."
)
print(result)  # {'answer': 'a subset of AI that allows computers to learn from data', ...}

# Named Entity Recognition
ner = pipeline("ner", grouped_entities=True)
result = ner("Elon Musk founded SpaceX in Hawthorne, California")
print(result)
# [{'entity_group': 'PER', 'word': 'Elon Musk', ...},
#  {'entity_group': 'ORG', 'word': 'SpaceX', ...},
#  {'entity_group': 'LOC', 'word': 'Hawthorne, California', ...}]
```

### Text Classification with BERT

```python
from transformers import BertTokenizer, TFBertForSequenceClassification
import tensorflow as tf

# Load pre-trained BERT
tokenizer = BertTokenizer.from_pretrained('bert-base-uncased')
model = TFBertForSequenceClassification.from_pretrained('bert-base-uncased', num_labels=2)

# Tokenize text
texts = ["This movie is great!", "Terrible waste of time"]
encoded = tokenizer(texts, padding=True, truncation=True, return_tensors='tf')

# Predict
outputs = model(encoded)
predictions = tf.nn.softmax(outputs.logits, axis=-1)
print(predictions)
```

### The Modern ML Landscape

| Area | Key Models | Application |
|------|-----------|-------------|
| **NLP** | GPT, BERT, T5, LLaMA | Chatbots, translation, summarization |
| **Computer Vision** | ViT, CLIP, Stable Diffusion | Image classification, generation |
| **Audio** | Whisper, WaveNet | Speech recognition, music generation |
| **Multimodal** | GPT-4V, Gemini, CLIP | Understanding text + images together |
| **Reinforcement Learning** | PPO, DQN, AlphaGo | Game playing, robotics |

### 🎯 Day 28 Practice

- Use Hugging Face pipelines for sentiment analysis, text generation, and NER.
- Fine-tune a BERT model for text classification.
- Explore the Hugging Face model hub and try different pre-trained models.

---

---

# 🗓️ FINALE: Days 29–30

---

## Day 29: Capstone Project

### Build a Complete End-to-End ML Project

Choose ONE of the following projects and build it from scratch:

#### Project Option 1: House Price Prediction

**Type:** Supervised Learning (Regression)

**Steps:**
1. Download the Kaggle Housing Prices dataset.
2. Perform EDA (Exploratory Data Analysis).
3. Preprocess data (handle missing values, encode categoricals, scale features).
4. Engineer new features (price per sq ft, age of house, etc.).
5. Compare models: Linear Regression, Ridge, Lasso, Random Forest, XGBoost.
6. Tune the best model using GridSearchCV.
7. Evaluate using MSE, RMSE, and R².
8. Create visualizations of predictions vs actual.

#### Project Option 2: Image Classification

**Type:** Deep Learning (CNN + Transfer Learning)

**Steps:**
1. Choose a dataset (CIFAR-10, Flowers, or your own).
2. Build a custom CNN.
3. Apply transfer learning with MobileNetV2 or ResNet50.
4. Fine-tune the model.
5. Evaluate with accuracy, confusion matrix.
6. Visualize predictions.

#### Project Option 3: Sentiment Analysis

**Type:** NLP

**Steps:**
1. Download the IMDB Movie Reviews dataset.
2. Preprocess text (tokenize, clean, vectorize).
3. Compare: TF-IDF + Logistic Regression vs LSTM vs BERT.
4. Evaluate with accuracy, precision, recall, F1.
5. Deploy as a simple API (optional).

#### Project Option 4: Customer Churn Prediction

**Type:** Supervised Learning (Classification)

**Steps:**
1. Download a telecom churn dataset.
2. Perform EDA.
3. Handle class imbalance (SMOTE, undersampling).
4. Feature engineering.
5. Compare models: Logistic Regression, Random Forest, XGBoost, Neural Network.
6. Focus on recall (catching churners is important!).
7. Build a complete pipeline.

### 🎯 Day 29 Practice

- Complete your chosen project.
- Write clean, well-commented code.
- Create a README explaining your approach and results.

---

## Day 30: Review, Reflect, and Next Steps

### 30-Day Review Checklist

#### ✅ Foundations (Week 1)
- [ ] Python basics (variables, loops, functions, data structures)
- [ ] NumPy (arrays, operations, broadcasting)
- [ ] Pandas (DataFrames, selection, grouping, missing data)
- [ ] Data visualization (Matplotlib, Seaborn)
- [ ] Math essentials (linear algebra, statistics, probability)
- [ ] Data preprocessing (encoding, scaling, splitting)

#### ✅ Core ML (Week 2)
- [ ] Linear Regression (MSE, R², gradient descent)
- [ ] Logistic Regression (sigmoid, log loss, classification metrics)
- [ ] Decision Trees (Gini, entropy, information gain)
- [ ] Random Forest & Ensemble Methods (bagging, boosting, XGBoost)
- [ ] KNN & Naive Bayes
- [ ] SVM (kernels, C, gamma)
- [ ] Model evaluation (cross-validation, grid search, bias-variance tradeoff)

#### ✅ Advanced ML (Week 3)
- [ ] Feature Engineering (polynomial, binning, log transform)
- [ ] Regularization (Ridge, Lasso, Elastic Net)
- [ ] K-Means Clustering (elbow method, silhouette score)
- [ ] Hierarchical Clustering & DBSCAN
- [ ] PCA (dimensionality reduction)
- [ ] ML Pipelines (ColumnTransformer, GridSearchCV)

#### ✅ Deep Learning (Week 4)
- [ ] Neural Networks (perceptrons, activation functions, backpropagation)
- [ ] Training Techniques (dropout, batch norm, early stopping)
- [ ] CNNs (convolution, pooling, architectures)
- [ ] Transfer Learning (fine-tuning, pre-trained models)
- [ ] NLP (tokenization, TF-IDF, word embeddings)
- [ ] RNNs & LSTMs (sequential data, time series)
- [ ] Transformers (attention, BERT, GPT)

### 🗺️ Where to Go Next

#### 1. Practice Platforms
- **Kaggle** — Competitions, datasets, notebooks
- **Google Colab** — Free GPU for deep learning
- **LeetCode ML** — ML coding challenges

#### 2. Advanced Topics to Explore
- **Generative AI** — GANs, VAEs, Diffusion Models
- **Reinforcement Learning** — Q-Learning, Policy Gradients
- **MLOps** — Model deployment, monitoring, CI/CD
- **AutoML** — Automated model selection and tuning
- **Explainable AI** — SHAP, LIME (understanding model predictions)
- **Federated Learning** — Training on distributed, private data

#### 3. Recommended Resources
- **Courses:**
  - Andrew Ng's ML Specialization (Coursera)
  - fast.ai — Practical Deep Learning
  - Stanford CS229 (Machine Learning)
  - Stanford CS231n (Computer Vision)

- **Books:**
  - "Hands-On Machine Learning" by Aurélien Géron
  - "Deep Learning" by Ian Goodfellow
  - "Pattern Recognition and Machine Learning" by Bishop

- **YouTube Channels:**
  - 3Blue1Brown (Neural Networks series)
  - StatQuest (Statistics & ML made simple)
  - Sentdex (Practical ML tutorials)

### 🎯 Final Tips

1. **Build projects, not just follow tutorials.** The best way to learn is by doing.
2. **Participate in Kaggle competitions.** Even if you don't win, you'll learn from others.
3. **Read research papers.** Start with survey papers and work your way to cutting-edge research.
4. **Teach what you learn.** Write blog posts, make videos, or explain to others.
5. **Stay curious.** ML is a rapidly evolving field — keep learning!

---

> 🎓 **Congratulations!** You've completed the 30-Day Machine Learning Plan. You now have a solid foundation in ML. Remember: **consistency beats intensity**. Keep practicing, keep building, and keep learning. The journey is just beginning! 🚀

---

*Created with ❤️ for aspiring Machine Learning engineers.*
