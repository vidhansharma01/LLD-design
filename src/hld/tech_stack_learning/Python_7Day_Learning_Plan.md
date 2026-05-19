# 🐍 7-Day Python Learning Plan for Beginners

> **Goal:** Go from zero to writing real Python programs in 7 days.
> Each day builds on the last. Practice every example — typing code yourself is 10× better than reading.

---

## 📅 Day 1 — Python Basics: Setup, Variables & Data Types

### 1.1 Setting Up Python

1. Download Python from [python.org](https://python.org) (3.11+)
2. Install VS Code + the Python extension
3. Open terminal, type `python --version` to verify

```bash
# Verify installation
python --version   # Python 3.11.x
```

### 1.2 Your First Program

```python
# hello.py
print("Hello, World!")
print("Welcome to Python!")
```

`print()` is a **built-in function** that outputs text to the console.

---

### 1.3 Variables

A variable is a named container that stores a value.

```python
name = "Alice"         # str
age = 25               # int
height = 5.7           # float
is_student = True      # bool

print(name, age, height, is_student)
```

**Rules for variable names:**
- Must start with a letter or `_`
- Cannot use spaces (use `snake_case` instead)
- Case-sensitive: `age` ≠ `Age`

---

### 1.4 Data Types

| Type | Example | Description |
|------|---------|-------------|
| `int` | `42` | Whole numbers |
| `float` | `3.14` | Decimal numbers |
| `str` | `"hello"` | Text |
| `bool` | `True/False` | True or False |
| `NoneType` | `None` | Absence of value |

```python
x = 10
y = 3.5
text = "Python"
flag = False
empty = None

print(type(x))      # <class 'int'>
print(type(text))   # <class 'str'>
```

---

### 1.5 Type Conversion

```python
age_str = "25"
age_int = int(age_str)      # "25" → 25
pi_str = str(3.14)          # 3.14 → "3.14"
num = float("9.99")         # "9.99" → 9.99

print(age_int + 5)          # 30
```

---

### 1.6 Taking User Input

```python
name = input("What is your name? ")
age = int(input("How old are you? "))
print(f"Hello {name}, you are {age} years old.")
```

---

### 🏋️ Day 1 Exercises

1. Create variables for your name, age, city. Print them all.
2. Take two numbers as input and print their sum.
3. Convert `"100"` to int, add 50, print the result.

---

## 📅 Day 2 — Operators, Strings & String Formatting

### 2.1 Arithmetic Operators

```python
a = 10
b = 3

print(a + b)    # 13   — Addition
print(a - b)    # 7    — Subtraction
print(a * b)    # 30   — Multiplication
print(a / b)    # 3.33 — Division (always float)
print(a // b)   # 3    — Floor division
print(a % b)    # 1    — Modulo (remainder)
print(a ** b)   # 1000 — Exponentiation
```

---

### 2.2 Comparison Operators

These return `True` or `False`.

```python
x = 10
print(x == 10)   # True  — Equal
print(x != 5)    # True  — Not equal
print(x > 7)     # True  — Greater than
print(x < 7)     # False — Less than
print(x >= 10)   # True  — Greater or equal
print(x <= 10)   # True  — Less or equal
```

---

### 2.3 Logical Operators

```python
a = True
b = False

print(a and b)   # False — Both must be True
print(a or b)    # True  — At least one True
print(not a)     # False — Flip the value
```

---

### 2.4 String Operations

```python
first = "Hello"
last = "World"

greeting = first + " " + last
print(greeting)         # Hello World

print("Ha" * 3)         # HaHaHa
print(len(greeting))    # 11
print(greeting[0])      # H
print(greeting[-1])     # d
```

---

### 2.5 String Slicing

```python
text = "Python"

print(text[0:3])    # Pyt
print(text[2:])     # thon
print(text[:4])     # Pyth
print(text[::-1])   # nohtyP (reversed)
```

---

### 2.6 Common String Methods

```python
s = "  hello world  "

print(s.upper())              # "  HELLO WORLD  "
print(s.strip())              # "hello world"
print(s.strip().title())      # "Hello World"
print(s.strip().replace("world", "Python"))

words = "apple,banana,cherry"
print(words.split(","))       # ['apple', 'banana', 'cherry']

parts = ["one", "two", "three"]
print("-".join(parts))        # "one-two-three"

print("hello".startswith("he"))  # True
print("hello".find("ll"))        # 2
```

---

### 2.7 f-Strings (Formatted String Literals)

```python
name = "Alice"
age = 25
score = 95.678

print(f"Name: {name}, Age: {age}")
print(f"Score: {score:.2f}")      # 2 decimal places
print(f"Next year: {age + 1}")
```

---

### 🏋️ Day 2 Exercises

1. Write a program to check if a number is even or odd using `%`.
2. Take a sentence as input, print it reversed and uppercase.
3. Calculate area of a rectangle using input values.

---

## 📅 Day 3 — Control Flow: if/elif/else & Loops

### 3.1 if / elif / else

```python
score = int(input("Enter your score: "))

if score >= 90:
    print("Grade: A")
elif score >= 80:
    print("Grade: B")
elif score >= 70:
    print("Grade: C")
else:
    print("Grade: F")
```

**Indentation matters in Python** — always use 4 spaces.

---

### 3.2 Nested if Statements

```python
age = 20
has_id = True

if age >= 18:
    if has_id:
        print("Entry allowed")
    else:
        print("Need ID")
else:
    print("Too young")
```

---

### 3.3 Ternary (One-line if)

```python
x = 10
result = "Even" if x % 2 == 0 else "Odd"
print(result)   # Even
```

---

### 3.4 for Loops

```python
for i in range(5):
    print(i)          # 0 1 2 3 4

for i in range(1, 10, 2):
    print(i)          # 1 3 5 7 9

for char in "Python":
    print(char)
```

---

### 3.5 while Loops

```python
count = 0
while count < 5:
    print(f"Count: {count}")
    count += 1

while True:
    answer = input("Type 'quit' to exit: ")
    if answer == "quit":
        break
    print(f"You typed: {answer}")
```

---

### 3.6 break, continue, pass

```python
# break — exit loop early
for i in range(10):
    if i == 5:
        break
    print(i)           # 0 1 2 3 4

# continue — skip current iteration
for i in range(10):
    if i % 2 == 0:
        continue
    print(i)           # 1 3 5 7 9

# pass — placeholder (does nothing)
for i in range(5):
    pass
```

---

### 🏋️ Day 3 Exercises

1. Print all numbers 1–100 divisible by 3 or 5.
2. Use a while loop to keep asking for a password until correct.
3. Print a multiplication table for a number entered by user.

---

## 📅 Day 4 — Data Structures: Lists, Tuples, Sets & Dictionaries

### 4.1 Lists

An ordered, mutable collection.

```python
fruits = ["apple", "banana", "cherry"]

print(fruits[0])       # apple
print(fruits[-1])      # cherry

fruits[1] = "blueberry"
fruits.append("mango")
fruits.insert(1, "kiwi")

fruits.remove("apple")
popped = fruits.pop()
del fruits[0]

print(len(fruits))
print("mango" in fruits)
print(fruits[1:3])
```

---

### 4.2 Useful List Methods

```python
nums = [3, 1, 4, 1, 5, 9, 2, 6]

nums.sort()
nums.sort(reverse=True)
print(sorted(nums))         # Returns new sorted list

nums.reverse()
print(nums.count(1))        # Count occurrences
print(nums.index(5))        # Index of value
copy = nums.copy()
nums.clear()
```

---

### 4.3 List Comprehensions

```python
squares = [x**2 for x in range(1, 6)]
print(squares)            # [1, 4, 9, 16, 25]

evens = [x for x in range(20) if x % 2 == 0]
print(evens)

words = ["hello", "world"]
upper = [w.upper() for w in words]
print(upper)              # ['HELLO', 'WORLD']
```

---

### 4.4 Tuples

Ordered, **immutable** collection.

```python
point = (3, 7)
colors = ("red", "green", "blue")

print(point[0])   # 3

# Unpacking
x, y = point
r, g, b = colors

# Use tuples for: coordinates, RGB, DB records
```

---

### 4.5 Sets

Unordered, **unique** elements.

```python
my_set = {1, 2, 3, 2, 1}
print(my_set)             # {1, 2, 3}

my_set.add(4)
my_set.remove(2)
my_set.discard(99)        # No error if missing

a = {1, 2, 3, 4}
b = {3, 4, 5, 6}

print(a | b)              # Union: {1,2,3,4,5,6}
print(a & b)              # Intersection: {3,4}
print(a - b)              # Difference: {1,2}

# Remove duplicates
unique = list(set([1, 2, 2, 3, 3]))
```

---

### 4.6 Dictionaries

Key-value pairs. Keys must be unique and immutable.

```python
person = {
    "name": "Alice",
    "age": 25,
    "city": "Mumbai"
}

print(person["name"])
print(person.get("salary", 0))   # Default if missing

person["age"] = 26
person["email"] = "alice@email.com"
del person["city"]

for key, value in person.items():
    print(f"{key} = {value}")

print("name" in person)  # True
```

---

### 4.7 Nested Data

```python
students = [
    {"name": "Alice", "grade": 90},
    {"name": "Bob",   "grade": 85},
]

for s in students:
    print(f"{s['name']}: {s['grade']}")

students.sort(key=lambda s: s["grade"], reverse=True)
```

---

### 🏋️ Day 4 Exercises

1. Create a list of 10 numbers. Print sum, avg, min, max.
2. Count word frequency in a sentence using a dictionary.
3. Remove duplicates from `[1,2,2,3,4,4,5]` using a set.

---

## 📅 Day 5 — Functions & Modules

### 5.1 Defining Functions

```python
def greet(name):
    """Greets a person by name."""
    print(f"Hello, {name}!")

greet("Alice")
```

---

### 5.2 Return Values

```python
def add(a, b):
    return a + b

result = add(3, 5)   # 8

# Return multiple values
def min_max(numbers):
    return min(numbers), max(numbers)

lo, hi = min_max([3, 1, 9, 4])
```

---

### 5.3 Default & Keyword Arguments

```python
def greet(name, greeting="Hello"):
    print(f"{greeting}, {name}!")

greet("Alice")
greet("Bob", "Hi")
greet(greeting="Hey", name="Carol")
```

---

### 5.4 *args and **kwargs

```python
def total(*numbers):
    return sum(numbers)

print(total(1, 2, 3))        # 6
print(total(10, 20, 30, 40)) # 100

def show_info(**details):
    for key, value in details.items():
        print(f"{key}: {value}")

show_info(name="Alice", age=25, city="Mumbai")
```

---

### 5.5 Lambda Functions

```python
square = lambda x: x ** 2
print(square(5))              # 25

nums = [5, 2, 8, 1, 9]
nums.sort(key=lambda x: -x)
print(nums)                   # [9, 8, 5, 2, 1]
```

---

### 5.6 map(), filter(), zip()

```python
nums = [1, 2, 3, 4, 5]

doubled = list(map(lambda x: x * 2, nums))
evens = list(filter(lambda x: x % 2 == 0, nums))

names = ["Alice", "Bob"]
scores = [90, 85]
for name, score in zip(names, scores):
    print(f"{name}: {score}")
```

---

### 5.7 Scope: Local vs Global

```python
x = 10  # Global

def my_func():
    x = 20     # Local
    print(x)   # 20

my_func()
print(x)       # 10

def change_global():
    global x
    x = 99

change_global()
print(x)       # 99
```

---

### 5.8 Importing Modules

```python
import math
print(math.sqrt(16))         # 4.0
print(math.pi)               # 3.14159...

import random
print(random.randint(1, 10))

from datetime import datetime
now = datetime.now()
print(now.strftime("%Y-%m-%d %H:%M:%S"))

import os
print(os.getcwd())
```

---

### 🏋️ Day 5 Exercises

1. Write a function that checks if a number is prime.
2. Write a function using `*args` to find the average of any numbers.
3. Use `map()` to convert a list of Celsius temperatures to Fahrenheit.

---

## 📅 Day 6 — Object-Oriented Programming (OOP)

### 6.1 Classes and Objects

```python
class Dog:
    species = "Canis lupus familiaris"   # Class attribute

    def __init__(self, name, age):       # Constructor
        self.name = name
        self.age = age

    def bark(self):
        print(f"{self.name} says: Woof!")

    def info(self):
        print(f"{self.name} is {self.age} years old.")


dog1 = Dog("Buddy", 3)
dog2 = Dog("Max", 5)

dog1.bark()
dog2.info()
print(Dog.species)
```

---

### 6.2 Inheritance

```python
class Animal:
    def __init__(self, name):
        self.name = name

    def speak(self):
        raise NotImplementedError("Subclass must implement this")


class Dog(Animal):
    def speak(self):
        return f"{self.name} says Woof!"


class Cat(Animal):
    def speak(self):
        return f"{self.name} says Meow!"


animals = [Dog("Buddy"), Cat("Whiskers")]
for animal in animals:
    print(animal.speak())
```

---

### 6.3 Encapsulation

```python
class BankAccount:
    def __init__(self, owner, balance):
        self.owner = owner
        self.__balance = balance       # Private attribute

    def deposit(self, amount):
        if amount > 0:
            self.__balance += amount

    def withdraw(self, amount):
        if amount > self.__balance:
            print("Insufficient funds")
        else:
            self.__balance -= amount

    def get_balance(self):
        return self.__balance


acc = BankAccount("Alice", 1000)
acc.deposit(500)
acc.withdraw(200)
print(acc.get_balance())   # 1300
```

---

### 6.4 Special (Dunder) Methods

```python
class Vector:
    def __init__(self, x, y):
        self.x = x
        self.y = y

    def __repr__(self):
        return f"Vector({self.x}, {self.y})"

    def __add__(self, other):
        return Vector(self.x + other.x, self.y + other.y)

    def __eq__(self, other):
        return self.x == other.x and self.y == other.y


v1 = Vector(2, 3)
v2 = Vector(1, 4)
print(v1)          # Vector(2, 3)
print(v1 + v2)     # Vector(3, 7)
print(v1 == v2)    # False
```

---

### 6.5 Class & Static Methods

```python
class Circle:
    PI = 3.14159

    def __init__(self, radius):
        self.radius = radius

    def area(self):
        return Circle.PI * self.radius ** 2

    @classmethod
    def from_diameter(cls, diameter):
        return cls(diameter / 2)

    @staticmethod
    def is_valid_radius(r):
        return r > 0


c1 = Circle(5)
c2 = Circle.from_diameter(10)
print(c1.area())
print(Circle.is_valid_radius(-1))   # False
```

---

### 🏋️ Day 6 Exercises

1. Create a `Rectangle` class with `area()` and `perimeter()` methods.
2. Create a `Student` class with a class method to track total students.
3. Implement a `Stack` class with `push`, `pop`, `peek`, `is_empty`.

---

## 📅 Day 7 — File I/O, Error Handling & Next Steps

### 7.1 Reading & Writing Files

```python
# Writing
with open("notes.txt", "w") as f:
    f.write("Hello, World!\n")
    f.write("Python is awesome!\n")

# Reading entire file
with open("notes.txt", "r") as f:
    content = f.read()
    print(content)

# Reading line by line
with open("notes.txt", "r") as f:
    for line in f:
        print(line.strip())

# Appending
with open("notes.txt", "a") as f:
    f.write("New line added.\n")
```

> **Why `with`?** It automatically closes the file even if an error occurs.

---

### 7.2 Working with CSV Files

```python
import csv

# Writing
data = [["Name", "Age"], ["Alice", 25], ["Bob", 30]]
with open("people.csv", "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerows(data)

# Reading
with open("people.csv", "r") as f:
    reader = csv.DictReader(f)
    for row in reader:
        print(row["Name"], row["Age"])
```

---

### 7.3 Working with JSON

```python
import json

person = {"name": "Alice", "age": 25, "skills": ["Python", "SQL"]}

# Dict → JSON string
json_str = json.dumps(person, indent=2)
print(json_str)

# JSON string → Dict
data = json.loads(json_str)
print(data["name"])

# Write to file
with open("data.json", "w") as f:
    json.dump(person, f, indent=2)

# Read from file
with open("data.json", "r") as f:
    loaded = json.load(f)
```

---

### 7.4 Exception Handling

```python
try:
    x = int(input("Enter a number: "))
    result = 10 / x
    print(f"Result: {result}")
except ValueError:
    print("That's not a valid number!")
except ZeroDivisionError:
    print("Cannot divide by zero!")
except Exception as e:
    print(f"Unexpected error: {e}")
else:
    print("Everything went fine!")
finally:
    print("This always runs.")
```

---

### 7.5 Custom Exceptions

```python
class InsufficientFundsError(Exception):
    def __init__(self, amount, balance):
        super().__init__(f"Cannot withdraw {amount}. Balance: {balance}")
        self.amount = amount
        self.balance = balance


def withdraw(balance, amount):
    if amount > balance:
        raise InsufficientFundsError(amount, balance)
    return balance - amount


try:
    withdraw(500, 800)
except InsufficientFundsError as e:
    print(e)
```

---

### 7.6 Common Exceptions Reference

| Exception | Cause |
|-----------|-------|
| `ValueError` | Wrong value type passed |
| `TypeError` | Wrong type used |
| `KeyError` | Dict key missing |
| `IndexError` | List index out of range |
| `FileNotFoundError` | File doesn't exist |
| `ZeroDivisionError` | Division by zero |
| `AttributeError` | Object has no such attribute |
| `ImportError` | Module not found |

---

### 7.7 Virtual Environments & pip

```bash
# Create virtual environment
python -m venv venv

# Activate (Windows)
venv\Scripts\activate

# Install packages
pip install requests pandas numpy

# Save dependencies
pip freeze > requirements.txt

# Install from requirements
pip install -r requirements.txt
```

---

### 🏋️ Day 7 Exercises

1. Read a CSV of students and print the top 3 by score.
2. Build a contact book that saves/loads contacts from a JSON file.
3. Write a safe calculator that handles divide-by-zero and invalid input.

---

## 🗺️ Full Week Recap

| Day | Topics Covered |
|-----|---------------|
| Day 1 | Variables, Data Types, Input/Output |
| Day 2 | Operators, Strings, f-Strings |
| Day 3 | if/else, for, while, break/continue |
| Day 4 | Lists, Tuples, Sets, Dictionaries |
| Day 5 | Functions, Modules, Lambda, map/filter |
| Day 6 | OOP — Classes, Inheritance, Encapsulation |
| Day 7 | File I/O, JSON, Exception Handling |

---

## 🚀 What to Learn Next (Week 2+)

| Topic | Why |
|-------|-----|
| Decorators & Generators | Advanced function patterns |
| Regular Expressions (`re`) | Text parsing & validation |
| `asyncio` / `threading` | Concurrency |
| `pytest` | Unit testing |
| `fastapi` / `flask` | Web APIs |
| `pandas` + `numpy` | Data science |
| `sqlalchemy` | Database ORM |

---

## 💡 Tips for Success

1. **Type every example** — don't copy-paste
2. **Break things on purpose** — best way to understand errors
3. **Build mini-projects** — calculator, to-do list, quiz app
4. **Use the REPL** — type `python` in terminal to experiment
5. **Read error messages** — Python errors are very descriptive
6. **Search Stack Overflow** — every developer does it

---

*Happy coding! 🐍*
