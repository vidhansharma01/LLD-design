# Matplotlib 7-Day Learning Plan for Beginners

> **Goal:** Go from zero to confident Matplotlib user in 7 days.
> **Prerequisites:** Basic Python, basic NumPy.

---

## Day 1 — Introduction & Your First Plot

### What is Matplotlib?
Matplotlib is Python's most widely used plotting library. It provides full control over every element of a figure — from simple line charts to complex multi-panel scientific visualizations.

```bash
pip install matplotlib
```

### The Two Interfaces

**1. pyplot (MATLAB-style) — quick and simple**
```python
import matplotlib.pyplot as plt
import numpy as np

x = np.linspace(0, 10, 100)
y = np.sin(x)

plt.plot(x, y)
plt.title("Sine Wave")
plt.xlabel("x")
plt.ylabel("sin(x)")
plt.show()
```

**2. Object-Oriented (OO) — recommended for full control**
```python
fig, ax = plt.subplots()
ax.plot(x, y)
ax.set_title("Sine Wave")
ax.set_xlabel("x")
ax.set_ylabel("sin(x)")
plt.show()
```

> **Rule:** Always prefer the OO interface for anything beyond a single quick plot.

### Anatomy of a Figure

```
Figure
└── Axes (one or more subplots)
    ├── Title
    ├── X-axis (label, ticks, ticklabels, limits)
    ├── Y-axis (label, ticks, ticklabels, limits)
    ├── Lines / Artists
    └── Legend
```

```python
fig, ax = plt.subplots(figsize=(8, 5))  # width=8in, height=5in

ax.plot(x, np.sin(x), label='sin(x)')
ax.plot(x, np.cos(x), label='cos(x)')

ax.set_title("Trig Functions", fontsize=16, fontweight='bold')
ax.set_xlabel("x axis", fontsize=12)
ax.set_ylabel("y axis", fontsize=12)
ax.set_xlim(0, 10)
ax.set_ylim(-1.5, 1.5)
ax.legend()
ax.grid(True, linestyle='--', alpha=0.5)

plt.tight_layout()
plt.show()
```

### Saving Figures

```python
fig.savefig('plot.png', dpi=150, bbox_inches='tight')
fig.savefig('plot.pdf')   # vector format
fig.savefig('plot.svg')   # scalable vector
```

### Practice Exercises — Day 1
1. Plot `y = x²` for x from -10 to 10. Add title, labels, and grid.
2. Plot `sin(x)`, `cos(x)`, and `tan(x)` on the same axes with a legend.
3. Save your plot as both `.png` and `.pdf`.

---

## Day 2 — Line Plots & Styling

### Line Styling

```python
fig, ax = plt.subplots()
x = np.linspace(0, 2*np.pi, 100)

ax.plot(x, np.sin(x), color='blue',   linestyle='-',  linewidth=2,  label='solid')
ax.plot(x, np.cos(x), color='red',    linestyle='--', linewidth=1.5, label='dashed')
ax.plot(x, np.sin(2*x), color='green', linestyle='-.',              label='dashdot')
ax.plot(x, np.cos(2*x), color='purple', linestyle=':',              label='dotted')
ax.legend()
plt.show()
```

### Colors

```python
# Named colors
ax.plot(x, y, color='tomato')
ax.plot(x, y, color='steelblue')

# Hex codes
ax.plot(x, y, color='#FF5733')

# RGBA tuple (red, green, blue, alpha)
ax.plot(x, y, color=(0.2, 0.4, 0.8, 0.7))

# Short codes: 'r','g','b','c','m','y','k','w'
ax.plot(x, y, 'r--')   # red dashed
ax.plot(x, y, 'bo-')   # blue circle markers + line
```

### Markers

```python
x = np.arange(10)
ax.plot(x, x**2, marker='o', markersize=8, markerfacecolor='white',
        markeredgecolor='blue', markeredgewidth=2)

# Marker styles:
# 'o' circle  's' square  '^' triangle  'D' diamond
# '*' star    '+' plus    'x' x         '|' vline
```

### Format String Shorthand

```python
ax.plot(x, y, 'r--o')   # red, dashed, circle markers
ax.plot(x, y, 'g:^')    # green, dotted, triangle markers
ax.plot(x, y, 'b-s')    # blue, solid, square markers
```

### Axes Ticks & Labels

```python
ax.set_xticks([0, np.pi/2, np.pi, 3*np.pi/2, 2*np.pi])
ax.set_xticklabels(['0', 'π/2', 'π', '3π/2', '2π'])
ax.tick_params(axis='x', rotation=45, labelsize=10)
ax.tick_params(axis='y', colors='blue')
```

### Annotations

```python
ax.annotate('Maximum',
            xy=(np.pi/2, 1),            # point to annotate
            xytext=(np.pi/2 + 1, 0.8),  # text position
            arrowprops=dict(arrowstyle='->', color='black'),
            fontsize=12)

ax.text(3, 0.5, 'Note here', fontsize=10, color='gray',
        bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
```

### Practice Exercises — Day 2
1. Plot 4 different functions with different line styles and colors. Add markers to each.
2. Create a plot of daily temperatures for a week; annotate the highest and lowest points.
3. Customize the x-axis ticks to show month names instead of numbers.

---

## Day 3 — Subplots & Layouts

### Basic Subplots

```python
# Single row
fig, axes = plt.subplots(1, 3, figsize=(12, 4))
axes[0].plot(x, np.sin(x)); axes[0].set_title('sin')
axes[1].plot(x, np.cos(x)); axes[1].set_title('cos')
axes[2].plot(x, np.tan(x)); axes[2].set_title('tan')
plt.tight_layout()

# Grid of subplots
fig, axes = plt.subplots(2, 2, figsize=(10, 8))
axes[0, 0].plot(x, np.sin(x))
axes[0, 1].plot(x, np.cos(x))
axes[1, 0].plot(x, x**2)
axes[1, 1].plot(x, np.sqrt(np.abs(x)))
plt.tight_layout()
```

### Sharing Axes

```python
fig, (ax1, ax2) = plt.subplots(2, 1, sharex=True, figsize=(8, 6))
ax1.plot(x, np.sin(x))
ax2.plot(x, np.cos(x))
# Both subplots now zoom/pan together on x-axis
```

### GridSpec — Custom Layouts

```python
import matplotlib.gridspec as gridspec

fig = plt.figure(figsize=(10, 8))
gs = gridspec.GridSpec(2, 3)

ax1 = fig.add_subplot(gs[0, :])    # top: spans all 3 cols
ax2 = fig.add_subplot(gs[1, 0])    # bottom-left
ax3 = fig.add_subplot(gs[1, 1:])   # bottom: cols 1-2

ax1.plot(x, np.sin(x)); ax1.set_title('Wide Top Plot')
ax2.plot(x, x**2);      ax2.set_title('Small Left')
ax3.plot(x, np.cos(x)); ax3.set_title('Wide Right')
plt.tight_layout()
```

### Inset Axes

```python
from mpl_toolkits.axes_grid1.inset_locator import inset_axes

fig, ax = plt.subplots(figsize=(8, 6))
ax.plot(x, np.sin(x))

# Add a zoomed inset
axins = inset_axes(ax, width="40%", height="40%", loc='upper right')
axins.plot(x, np.sin(x))
axins.set_xlim(0, 2)
axins.set_ylim(0, 1)
```

### Twin Axes (dual y-axis)

```python
fig, ax1 = plt.subplots()
x = np.arange(12)
ax2 = ax1.twinx()  # share x, separate y

ax1.plot(x, np.random.randint(100,500,12), 'b-', label='Revenue')
ax2.plot(x, np.random.uniform(2,8,12), 'r--', label='Growth %')

ax1.set_ylabel('Revenue ($)', color='blue')
ax2.set_ylabel('Growth Rate (%)', color='red')
ax1.tick_params(axis='y', colors='blue')
ax2.tick_params(axis='y', colors='red')
```

### Practice Exercises — Day 3
1. Create a 2x3 grid of subplots, each with a different math function.
2. Use GridSpec to create a large main plot and two smaller subplots beside it.
3. Create a twin-axis plot showing temperature (line) and rainfall (bar) over 12 months.

---

## Day 4 — Chart Types

### Bar Charts

```python
categories = ['A', 'B', 'C', 'D', 'E']
values = [23, 45, 12, 67, 34]

fig, axes = plt.subplots(1, 2, figsize=(12, 5))

# Vertical bar
axes[0].bar(categories, values, color='steelblue', edgecolor='black', width=0.6)
axes[0].set_title('Vertical Bar Chart')
for i, v in enumerate(values):
    axes[0].text(i, v + 0.5, str(v), ha='center', fontweight='bold')

# Horizontal bar
axes[1].barh(categories, values, color='coral')
axes[1].set_title('Horizontal Bar Chart')
plt.tight_layout()

# Grouped bar chart
x = np.arange(4)
group1 = [20, 35, 30, 25]
group2 = [25, 32, 34, 20]
width = 0.35
fig, ax = plt.subplots()
ax.bar(x - width/2, group1, width, label='2023', color='steelblue')
ax.bar(x + width/2, group2, width, label='2024', color='coral')
ax.set_xticks(x); ax.set_xticklabels(['Q1','Q2','Q3','Q4'])
ax.legend()

# Stacked bar chart
ax.bar(x, group1, width, label='2023')
ax.bar(x, group2, width, bottom=group1, label='2024')
```

### Histograms

```python
data = np.random.normal(0, 1, 1000)
fig, axes = plt.subplots(1, 2, figsize=(12, 5))

axes[0].hist(data, bins=30, color='steelblue', edgecolor='black', alpha=0.7)
axes[0].set_title('Histogram')

# Density plot (normalized)
axes[1].hist(data, bins=30, density=True, alpha=0.6, color='green')
# Overlay normal curve
from scipy.stats import norm
xr = np.linspace(-4, 4, 100)
axes[1].plot(xr, norm.pdf(xr), 'r-', linewidth=2, label='Normal PDF')
axes[1].legend()
plt.tight_layout()

# Multiple histograms
fig, ax = plt.subplots()
ax.hist(np.random.normal(0,1,500), bins=25, alpha=0.5, label='Group A')
ax.hist(np.random.normal(2,1,500), bins=25, alpha=0.5, label='Group B')
ax.legend()
```

### Scatter Plots

```python
n = 200
x = np.random.randn(n)
y = 2*x + np.random.randn(n)
colors = np.random.rand(n)
sizes = np.random.randint(20, 200, n)

fig, ax = plt.subplots()
sc = ax.scatter(x, y, c=colors, s=sizes, alpha=0.7, cmap='viridis')
plt.colorbar(sc, ax=ax, label='Color Value')
ax.set_title('Scatter Plot with Color & Size')
```

### Pie Charts

```python
labels = ['Python', 'Java', 'C++', 'JS', 'Other']
sizes  = [35, 25, 15, 20, 5]
explode = (0.1, 0, 0, 0, 0)  # explode first slice

fig, ax = plt.subplots()
wedges, texts, autotexts = ax.pie(
    sizes, explode=explode, labels=labels,
    autopct='%1.1f%%', startangle=90,
    colors=['#FF6B6B','#4ECDC4','#45B7D1','#FFA07A','#98D8C8'],
    shadow=True)
ax.set_title('Programming Languages')
```

### Box Plots

```python
data = [np.random.normal(0, 1, 100),
        np.random.normal(1, 2, 100),
        np.random.normal(-1, 1.5, 100)]

fig, ax = plt.subplots()
bp = ax.boxplot(data, labels=['Group A', 'Group B', 'Group C'],
                patch_artist=True, notch=True)

colors = ['lightblue', 'lightgreen', 'lightyellow']
for patch, color in zip(bp['boxes'], colors):
    patch.set_facecolor(color)
ax.set_title('Box Plot Comparison')
```

### Practice Exercises — Day 4
1. Create a grouped bar chart comparing sales across 4 products for 3 years.
2. Generate two normally distributed datasets and overlay their histograms.
3. Create a scatter plot of height vs weight for 100 people; color by gender.

---

## Day 5 — Colormaps, Heatmaps & 2D Plots

### Colormaps

```python
# Sequential: viridis, plasma, inferno, magma, Blues, Greens
# Diverging:  RdBu, coolwarm, bwr, seismic
# Qualitative: tab10, Set1, Paired
# Cyclic:     hsv, twilight

data = np.random.rand(10, 10)
fig, axes = plt.subplots(1, 3, figsize=(15, 4))
for ax, cmap in zip(axes, ['viridis', 'RdBu', 'tab20']):
    im = ax.imshow(data, cmap=cmap)
    plt.colorbar(im, ax=ax)
    ax.set_title(cmap)
plt.tight_layout()
```

### Heatmaps

```python
# Correlation heatmap
import numpy as np
np.random.seed(42)
data = np.random.randn(6, 6)
corr = np.corrcoef(data)

fig, ax = plt.subplots(figsize=(8, 6))
im = ax.imshow(corr, cmap='coolwarm', vmin=-1, vmax=1)
plt.colorbar(im, ax=ax)

labels = ['A','B','C','D','E','F']
ax.set_xticks(range(6)); ax.set_xticklabels(labels)
ax.set_yticks(range(6)); ax.set_yticklabels(labels)

# Add text annotations
for i in range(6):
    for j in range(6):
        ax.text(j, i, f'{corr[i,j]:.2f}', ha='center', va='center',
                color='black', fontsize=8)
ax.set_title('Correlation Heatmap')
```

### Contour Plots

```python
x = np.linspace(-3, 3, 100)
y = np.linspace(-3, 3, 100)
X, Y = np.meshgrid(x, y)
Z = np.sin(X) * np.cos(Y)

fig, axes = plt.subplots(1, 3, figsize=(15, 4))

# Contour lines
axes[0].contour(X, Y, Z, levels=15, cmap='RdBu')
axes[0].set_title('Contour Lines')

# Filled contour
cf = axes[1].contourf(X, Y, Z, levels=15, cmap='RdBu')
plt.colorbar(cf, ax=axes[1])
axes[1].set_title('Filled Contour')

# imshow as heatmap
axes[2].imshow(Z, extent=[-3,3,-3,3], origin='lower', cmap='RdBu', aspect='auto')
axes[2].set_title('imshow')
plt.tight_layout()
```

### Colorbars

```python
fig, ax = plt.subplots()
sc = ax.scatter(np.random.rand(50), np.random.rand(50),
                c=np.random.rand(50), s=100, cmap='plasma')
cbar = plt.colorbar(sc, ax=ax)
cbar.set_label('Intensity', fontsize=12)
cbar.ax.tick_params(labelsize=10)
```

### Practice Exercises — Day 5
1. Plot the function `z = sin(r)/r` where `r = sqrt(x²+y²)` as a heatmap.
2. Create a correlation heatmap for a 5x5 random dataset with text annotations.
3. Plot filled contours for `z = x*exp(-x²-y²)`.

---

## Day 6 — Styles, Themes & Publication-Quality Plots

### Built-in Styles

```python
print(plt.style.available)   # see all styles

plt.style.use('seaborn-v0_8-whitegrid')
plt.style.use('ggplot')
plt.style.use('dark_background')
plt.style.use('bmh')
plt.style.use('fivethirtyeight')
plt.style.use('classic')

# Use temporarily (doesn't persist)
with plt.style.context('dark_background'):
    fig, ax = plt.subplots()
    ax.plot(x, np.sin(x))
    plt.show()
```

### Custom Style (rcParams)

```python
# Global settings
plt.rcParams['figure.figsize']     = (10, 6)
plt.rcParams['font.family']        = 'DejaVu Sans'
plt.rcParams['font.size']          = 12
plt.rcParams['axes.titlesize']     = 16
plt.rcParams['axes.labelsize']     = 13
plt.rcParams['axes.spines.top']    = False
plt.rcParams['axes.spines.right']  = False
plt.rcParams['lines.linewidth']    = 2
plt.rcParams['grid.alpha']         = 0.3
plt.rcParams['savefig.dpi']        = 150

# Reset to default
plt.rcdefaults()
```

### Custom Color Cycle

```python
from cycler import cycler
custom_colors = ['#E63946','#457B9D','#2A9D8F','#E9C46A','#F4A261']
plt.rcParams['axes.prop_cycle'] = cycler(color=custom_colors)
```

### Legend Customization

```python
x = np.linspace(0, 10, 100)
fig, ax = plt.subplots()
ax.plot(x, np.sin(x), label='sin(x)')
ax.plot(x, np.cos(x), label='cos(x)')

ax.legend(
    loc='upper right',          # position
    ncol=2,                     # columns
    fontsize=11,
    framealpha=0.8,             # transparency
    edgecolor='gray',
    fancybox=True,
    shadow=True,
    title='Functions',
    title_fontsize=12
)
```

### Publication-Quality Plot Template

```python
def make_publication_plot(x, y, xlabel, ylabel, title, filename):
    fig, ax = plt.subplots(figsize=(7, 5))

    ax.plot(x, y, color='#2196F3', linewidth=2, zorder=3)
    ax.fill_between(x, y, alpha=0.15, color='#2196F3')

    ax.set_title(title, fontsize=15, fontweight='bold', pad=15)
    ax.set_xlabel(xlabel, fontsize=12, labelpad=8)
    ax.set_ylabel(ylabel, fontsize=12, labelpad=8)

    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    ax.spines['left'].set_linewidth(0.8)
    ax.spines['bottom'].set_linewidth(0.8)

    ax.grid(True, axis='y', linestyle='--', alpha=0.4, zorder=0)
    ax.tick_params(labelsize=10, length=4)

    plt.tight_layout()
    fig.savefig(filename, dpi=200, bbox_inches='tight')
    return fig, ax
```

### Practice Exercises — Day 6
1. Apply 3 different built-in styles to the same plot and compare them.
2. Create a fully customized publication-quality line plot using `rcParams`.
3. Build a reusable function that generates a styled bar chart with custom colors.

---

## Day 7 — Projects & Putting It All Together

### Project 1: COVID Data Dashboard

```python
import numpy as np
import matplotlib.pyplot as plt

np.random.seed(42)
days = np.arange(1, 91)
cases     = np.cumsum(np.random.randint(100, 500, 90))
deaths    = np.cumsum(np.random.randint(1, 20, 90))
recovered = np.cumsum(np.random.randint(50, 300, 90))

fig = plt.figure(figsize=(14, 10))
fig.suptitle('COVID-19 Dashboard (90 Days)', fontsize=18, fontweight='bold', y=0.98)

gs = fig.add_gridspec(2, 2, hspace=0.4, wspace=0.3)

# Cumulative cases
ax1 = fig.add_subplot(gs[0, :])
ax1.fill_between(days, cases, alpha=0.3, color='tomato')
ax1.plot(days, cases, color='tomato', linewidth=2, label='Cases')
ax1.fill_between(days, recovered, alpha=0.3, color='green')
ax1.plot(days, recovered, color='green', linewidth=2, label='Recovered')
ax1.set_title('Cumulative Cases vs Recovered'); ax1.legend(); ax1.grid(alpha=0.3)

# Daily new cases
ax2 = fig.add_subplot(gs[1, 0])
daily = np.diff(cases, prepend=cases[0])
ax2.bar(days, daily, color='steelblue', alpha=0.7)
ax2.set_title('Daily New Cases'); ax2.grid(axis='y', alpha=0.3)

# Death rate pie
ax3 = fig.add_subplot(gs[1, 1])
active = cases[-1] - deaths[-1] - recovered[-1]
ax3.pie([recovered[-1], deaths[-1], active],
        labels=['Recovered','Deaths','Active'],
        autopct='%1.1f%%', colors=['#4CAF50','#F44336','#2196F3'],
        startangle=90)
ax3.set_title('Outcome Distribution')

plt.savefig('covid_dashboard.png', dpi=150, bbox_inches='tight')
plt.show()
```

### Project 2: Stock Price Analysis

```python
np.random.seed(0)
days = np.arange(252)
price = 100 + np.cumsum(np.random.randn(252) * 2)
volume = np.random.randint(1_000_000, 5_000_000, 252)

# 20-day and 50-day moving averages
ma20 = np.convolve(price, np.ones(20)/20, mode='valid')
ma50 = np.convolve(price, np.ones(50)/50, mode='valid')

fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 8),
                                 sharex=True, gridspec_kw={'height_ratios': [3,1]})

ax1.plot(days, price, color='#1E88E5', linewidth=1.5, label='Price', alpha=0.9)
ax1.plot(days[19:], ma20, color='orange', linewidth=2, label='MA20')
ax1.plot(days[49:], ma50, color='red',    linewidth=2, label='MA50')
ax1.fill_between(days, price, price.mean(), alpha=0.08,
                  where=price > price.mean(), color='green', label='Above avg')
ax1.set_title('Stock Price Analysis', fontsize=14, fontweight='bold')
ax1.set_ylabel('Price ($)'); ax1.legend(); ax1.grid(alpha=0.3)
ax1.spines['top'].set_visible(False); ax1.spines['right'].set_visible(False)

colors = ['green' if price[i] >= price[i-1] else 'red' for i in range(1, 252)]
colors = ['gray'] + colors
ax2.bar(days, volume, color=colors, alpha=0.7)
ax2.set_ylabel('Volume'); ax2.set_xlabel('Trading Day')
ax2.grid(axis='y', alpha=0.3)

plt.tight_layout()
plt.savefig('stock_analysis.png', dpi=150, bbox_inches='tight')
plt.show()
```

### Project 3: Machine Learning Results Visualizer

```python
np.random.seed(42)
epochs = np.arange(1, 51)
train_loss = 2.5 * np.exp(-0.1 * epochs) + np.random.rand(50) * 0.05
val_loss   = 2.5 * np.exp(-0.08 * epochs) + 0.2 + np.random.rand(50) * 0.08
train_acc  = 1 - train_loss/3
val_acc    = 1 - val_loss/3

fig, axes = plt.subplots(1, 2, figsize=(14, 5))
fig.suptitle('Model Training Results', fontsize=16, fontweight='bold')

for ax, metric, train, val, name in zip(
    axes,
    ['Loss', 'Accuracy'],
    [train_loss, train_acc],
    [val_loss, val_acc],
    ['loss', 'accuracy']
):
    ax.plot(epochs, train, 'b-', linewidth=2, label=f'Train {metric}')
    ax.plot(epochs, val,   'r--', linewidth=2, label=f'Val {metric}')
    best_epoch = np.argmin(val) if name == 'loss' else np.argmax(val)
    ax.axvline(best_epoch+1, color='gray', linestyle=':', alpha=0.7, label=f'Best epoch={best_epoch+1}')
    ax.set_title(f'Training & Validation {metric}')
    ax.set_xlabel('Epoch'); ax.set_ylabel(metric)
    ax.legend(); ax.grid(alpha=0.3)
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)

plt.tight_layout()
plt.savefig('training_curves.png', dpi=150, bbox_inches='tight')
plt.show()
```

### Day 7 Final Challenge

Build a **Sales Analytics Dashboard** with:
1. A line chart of monthly revenue for 2 years (with moving average).
2. A grouped bar chart of product-wise sales per quarter.
3. A pie chart of revenue by region.
4. A scatter plot of ad spend vs revenue (with correlation line).
5. Assemble all 4 into a single `GridSpec` figure with a shared title.
6. Save as a high-resolution PNG (300 DPI).

---

## Quick Reference Cheat Sheet

### Setup
```python
import matplotlib.pyplot as plt
import numpy as np
fig, ax = plt.subplots(figsize=(8, 5))
```

### Plot Types
```python
ax.plot(x, y)               # line
ax.bar(x, y)                # bar
ax.barh(x, y)               # horizontal bar
ax.scatter(x, y, c=, s=)    # scatter
ax.hist(data, bins=)        # histogram
ax.boxplot(data)            # box plot
ax.pie(sizes, labels=)      # pie
ax.imshow(Z, cmap=)         # image/heatmap
ax.contourf(X, Y, Z)        # filled contour
```

### Styling
```python
ax.set_title()  ax.set_xlabel()  ax.set_ylabel()
ax.set_xlim()   ax.set_ylim()
ax.set_xticks() ax.set_xticklabels()
ax.legend()     ax.grid()
ax.spines['top'].set_visible(False)
ax.annotate('text', xy=, xytext=, arrowprops=)
```

### Layout
```python
plt.subplots(rows, cols, figsize=, sharex=, sharey=)
gridspec.GridSpec(rows, cols)
plt.tight_layout()
```

### Save
```python
fig.savefig('file.png', dpi=150, bbox_inches='tight')
```

---

## Recommended Next Steps After Day 7

| Library | Purpose |
|---|---|
| **Seaborn** | Statistical plots built on Matplotlib (easier API) |
| **Plotly** | Interactive, web-based charts |
| **Bokeh** | Interactive browser visualizations |
| **Altair** | Declarative statistical visualization |
| **Pandas `.plot()`** | Quick plots directly from DataFrames |

> **Tip:** Seaborn is the natural next step — it wraps Matplotlib with a high-level API for statistical charts and beautiful defaults with far less code.
