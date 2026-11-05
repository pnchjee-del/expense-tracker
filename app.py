import io
import os
from datetime import datetime
from flask import Flask, render_template, request, redirect, url_for, send_file, jsonify
from flask_sqlalchemy import SQLAlchemy
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors

app = Flask(__name__)
database_url = os.environ.get('DATABASE_URL', 'sqlite:///database.db')
if database_url.startswith('postgres://'):
    database_url = database_url.replace('postgres://', 'postgresql://', 1)
app.config['SQLALCHEMY_DATABASE_URI'] = database_url
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
db = SQLAlchemy(app)

# Pre-defined categories
CATEGORIES = ['grocery', 'vegetables', 'fish & meat', 'cosmetics', 'cleaning materials', 'miscellaneous']
# Standard list of standard units for selection
UNITS = ['Kg', 'Gm', 'Litre', 'Ml', 'Pcs', 'Packet', 'Box']

class Expense(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    date = db.Column(db.Date, nullable=False, default=datetime.utcnow)
    category = db.Column(db.String(50), nullable=False)
    item_name = db.Column(db.String(100), nullable=False)
    quantity = db.Column(db.Float, nullable=False, default=1.0)
    unit = db.Column(db.String(20), nullable=False, default='Pcs')
    rate = db.Column(db.Float, nullable=False, default=0.0)
    amount = db.Column(db.Float, nullable=False) # Auto-calculated total purchase price

class ItemAutocomplete(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    category = db.Column(db.String(50), nullable=False)
    item_name = db.Column(db.String(100), nullable=False)
    __table_args__ = (db.UniqueConstraint('category', 'item_name', name='uq_category_item'),)

@app.route('/', methods=['GET'])
def index():
    expenses = Expense.query.order_by(Expense.date.desc(), Expense.id.desc()).all()
    total_amount = sum(e.amount for e in expenses)
    return render_template('index.html', expenses=expenses, total_amount=total_amount, categories=CATEGORIES, units=UNITS)

@app.route('/add_expense', methods=['POST'])
def add_expense():
    date_str = request.form.get('date')
    category = request.form.get('category')
    item_name = request.form.get('item_name', '').strip()
    try:
        quantity = float(request.form.get('quantity') or 1)
    except ValueError:
        quantity = 1.0
    unit = request.form.get('unit') or 'Pcs'
    try:
        rate = float(request.form.get('rate') or 0)
    except ValueError:
        rate = 0.0
    
    # Auto-calculate purchase price on backend safely
    amount = quantity * rate

    if date_str and category and item_name:
        expense_date = datetime.strptime(date_str, '%Y-%m-%d').date()
        
        new_expense = Expense(
            date=expense_date, 
            category=category, 
            item_name=item_name, 
            quantity=quantity,
            unit=unit,
            rate=rate,
            amount=amount
        )
        db.session.add(new_expense)

        existing_item = ItemAutocomplete.query.filter_by(category=category, item_name=item_name).first()
        if not existing_item:
            new_autocomplete = ItemAutocomplete(category=category, item_name=item_name)
            db.session.add(new_autocomplete)

        db.session.commit()

    return redirect(url_for('index'))

@app.route('/api/get_items', methods=['GET'])
def get_items():
    category = request.args.get('category')
    if category:
        items = ItemAutocomplete.query.filter_by(category=category).all()
        return jsonify([item.item_name for item in items])
    return jsonify([])

@app.route('/api/all_items', methods=['GET'])
def get_all_items():
    items = set()
    for exp in Expense.query.with_entities(Expense.item_name).distinct():
        if exp[0]:
            items.add(exp[0].strip())
    for ac in ItemAutocomplete.query.with_entities(ItemAutocomplete.item_name).distinct():
        if ac[0]:
            items.add(ac[0].strip())
    return jsonify(sorted(list(items), key=lambda s: s.lower()))

@app.route('/api/item_usage', methods=['GET'])
def item_usage():
    item_query = request.args.get('item_name', '').strip()
    start_str = request.args.get('start_date', '').strip()
    end_str = request.args.get('end_date', '').strip()

    if not item_query:
        return jsonify({'error': 'Please enter an item name'}), 400
    if not start_str or not end_str:
        return jsonify({'error': 'Please select start and end dates'}), 400

    try:
        start_date = datetime.strptime(start_str, '%Y-%m-%d').date()
        end_date = datetime.strptime(end_str, '%Y-%m-%d').date()
    except ValueError:
        return jsonify({'error': 'Invalid date format (expected YYYY-MM-DD)'}), 400

    # First attempt exact case-insensitive match
    expenses = Expense.query.filter(
        Expense.date.between(start_date, end_date),
        db.func.lower(Expense.item_name) == item_query.lower()
    ).order_by(Expense.date.asc()).all()

    # If no exact match, fallback to substring match
    if not expenses:
        expenses = Expense.query.filter(
            Expense.date.between(start_date, end_date),
            Expense.item_name.ilike(f"%{item_query}%")
        ).order_by(Expense.date.asc()).all()

    unit_quantities = {}
    total_spend = 0.0
    records = []

    for exp in expenses:
        total_spend += exp.amount
        unit_quantities[exp.unit] = unit_quantities.get(exp.unit, 0.0) + exp.quantity
        records.append({
            'id': exp.id,
            'date': exp.date.strftime('%Y-%m-%d'),
            'date_formatted': exp.date.strftime('%d-%m-%Y'),
            'category': exp.category.title(),
            'item_name': exp.item_name,
            'quantity': exp.quantity,
            'unit': exp.unit,
            'rate': exp.rate,
            'amount': exp.amount
        })

    qty_parts = [f"{qty:g} {unit}" for unit, qty in unit_quantities.items()]
    total_qty_str = ", ".join(qty_parts) if qty_parts else "0"

    avg_rate_str = "₹ 0.00"
    if len(unit_quantities) == 1:
        unit = list(unit_quantities.keys())[0]
        tot_q = list(unit_quantities.values())[0]
        if tot_q > 0:
            avg_rate_str = f"₹ {(total_spend / tot_q):,.2f} / {unit}"
    elif records:
        avg_r = sum(r['rate'] for r in records) / len(records)
        avg_rate_str = f"₹ {avg_r:,.2f} (avg)"

    return jsonify({
        'item_name': item_query,
        'matched_count': len(records),
        'start_date': start_str,
        'end_date': end_str,
        'total_spend': total_spend,
        'total_spend_formatted': f"₹ {total_spend:,.2f}",
        'total_quantity_str': total_qty_str,
        'avg_rate_str': avg_rate_str,
        'records': records
    })

@app.route('/api/spending_summary', methods=['GET'])
def spending_summary():
    expenses = Expense.query.order_by(Expense.date.asc()).all()
    category_data = {cat: 0.0 for cat in CATEGORIES}
    trend_data = {}

    for exp in expenses:
        if exp.category in category_data:
            category_data[exp.category] += exp.amount
        month_str = exp.date.strftime('%b %Y')
        trend_data[month_str] = trend_data.get(month_str, 0.0) + exp.amount

    return jsonify({
        "categories": [cat.title() for cat in category_data.keys()],
        "category_totals": list(category_data.values()),
        "trend_labels": list(trend_data.keys()),
        "trend_totals": list(trend_data.values())
    })

@app.route('/generate_report', methods=['GET'])
def generate_report():
    start_str = request.args.get('start_date')
    end_str = request.args.get('end_date')
    include_itemwise = request.args.get('include_itemwise') in ['true', '1', 'on', 'yes']

    if not start_str or not end_str:
        return "Please select a valid start and end date.", 400

    start_date = datetime.strptime(start_str, '%Y-%m-%d').date()
    end_date = datetime.strptime(end_str, '%Y-%m-%d').date()
    filtered_expenses = Expense.query.filter(Expense.date.between(start_date, end_date)).order_by(Expense.date.asc()).all()

    buffer = io.BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=letter, rightMargin=30, leftMargin=30, topMargin=40, bottomMargin=40)
    story = []

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle('ReportTitle', parent=styles['Heading1'], fontSize=20, spaceAfter=10, textColor=colors.HexColor('#1A365D'))
    subtitle_style = ParagraphStyle('ReportSubtitle', parent=styles['Normal'], fontSize=11, spaceAfter=20, textColor=colors.HexColor('#4A5568'))

    story.append(Paragraph("Detailed Expense Management Report", title_style))
    story.append(Paragraph(f"Period: {start_date.strftime('%d-%b-%Y')} to {end_date.strftime('%d-%b-%Y')}", subtitle_style))

    # Extended layout table columns to handle math fields
    data = [["Date", "Category", "Item Description", "Qty", "Unit", "Rate", "Total Price"]]
    grand_total = 0.0
    itemwise_map = {}

    for exp in filtered_expenses:
        data.append([
            exp.date.strftime('%d-%m-%Y'),
            exp.category.title(),
            exp.item_name,
            f"{exp.quantity:g}",
            exp.unit,
            f"{exp.rate:,.2f}",
            f"{exp.amount:,.2f}"
        ])
        grand_total += exp.amount

        # Item-wise aggregation
        key = (exp.item_name, exp.category.title(), exp.unit)
        if key not in itemwise_map:
            itemwise_map[key] = {'quantity': 0.0, 'amount': 0.0}
        itemwise_map[key]['quantity'] += exp.quantity
        itemwise_map[key]['amount'] += exp.amount

    data.append(["", "", "", "", "", "Grand Total:", f"{grand_total:,.2f}"])

    table = Table(data, colWidths=[65, 85, 140, 35, 45, 65, 85])
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#2B6CB0')),
        ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
        ('ALIGN', (0, 0), (2, -1), 'LEFT'),
        ('ALIGN', (3, 0), (-1, -1), 'RIGHT'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, 0), 10),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 6),
        ('TOPPADDING', (0, 0), (-1, 0), 6),
        ('BACKGROUND', (0, 1), (-1, -2), colors.HexColor('#F7FAFC')),
        ('GRID', (0, 0), (-1, -2), 0.5, colors.HexColor('#E2E8F0')),
        ('FONTNAME', (-2, -1), (-1, -1), 'Helvetica-Bold'),
        ('LINEABOVE', (-2, -1), (-1, -1), 1.5, colors.HexColor('#1A365D')),
    ]))

    story.append(table)

    # Optional Item-wise Summary Table if requested
    if include_itemwise and itemwise_map:
        story.append(Spacer(1, 20))
        section_style = ParagraphStyle('SectionHeading', parent=styles['Heading2'], fontSize=13, spaceAfter=8, textColor=colors.HexColor('#1A365D'))
        story.append(Paragraph("Item-wise Spending Summary", section_style))

        summary_data = [["Item Description", "Category", "Total Qty", "Unit", "Total Spend", "% of Total"]]
        sorted_items = sorted(itemwise_map.items(), key=lambda x: x[1]['amount'], reverse=True)
        for (name, cat, unit), vals in sorted_items:
            pct = (vals['amount'] / grand_total * 100) if grand_total > 0 else 0.0
            summary_data.append([
                name,
                cat,
                f"{vals['quantity']:g}",
                unit,
                f"{vals['amount']:,.2f}",
                f"{pct:.1f}%"
            ])

        summary_table = Table(summary_data, colWidths=[140, 95, 60, 45, 85, 55])
        summary_table.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#0F766E')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
            ('ALIGN', (0, 0), (1, -1), 'LEFT'),
            ('ALIGN', (2, 0), (-1, -1), 'RIGHT'),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
            ('FONTSIZE', (0, 0), (-1, 0), 9),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 5),
            ('TOPPADDING', (0, 0), (-1, -1), 5),
            ('BACKGROUND', (0, 1), (-1, -1), colors.HexColor('#F0FDFA')),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor('#CCFBF1')),
        ]))
        story.append(summary_table)

    doc.build(story)
    buffer.seek(0)
    return send_file(buffer, as_attachment=True, download_name=f"Detailed_Report_{start_str}_to_{end_str}.pdf", mimetype='application/pdf')

@app.route('/generate_item_report', methods=['GET'])
def generate_item_report():
    item_query = request.args.get('item_name', '').strip()
    start_str = request.args.get('start_date', '').strip()
    end_str = request.args.get('end_date', '').strip()

    if not item_query or not start_str or not end_str:
        return "Please provide item name, start date, and end date.", 400

    try:
        start_date = datetime.strptime(start_str, '%Y-%m-%d').date()
        end_date = datetime.strptime(end_str, '%Y-%m-%d').date()
    except ValueError:
        return "Invalid date format.", 400

    expenses = Expense.query.filter(
        Expense.date.between(start_date, end_date),
        db.func.lower(Expense.item_name) == item_query.lower()
    ).order_by(Expense.date.asc()).all()

    if not expenses:
        expenses = Expense.query.filter(
            Expense.date.between(start_date, end_date),
            Expense.item_name.ilike(f"%{item_query}%")
        ).order_by(Expense.date.asc()).all()

    buffer = io.BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=letter, rightMargin=30, leftMargin=30, topMargin=40, bottomMargin=40)
    story = []

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle('ReportTitle', parent=styles['Heading1'], fontSize=18, spaceAfter=6, textColor=colors.HexColor('#1E3A8A'))
    subtitle_style = ParagraphStyle('ReportSubtitle', parent=styles['Normal'], fontSize=11, spaceAfter=15, textColor=colors.HexColor('#4A5568'))

    story.append(Paragraph(f"Item Usage Statement: {item_query.title()}", title_style))
    story.append(Paragraph(f"Period: {start_date.strftime('%d-%b-%Y')} to {end_date.strftime('%d-%b-%Y')}", subtitle_style))

    unit_quantities = {}
    total_spend = 0.0
    for exp in expenses:
        total_spend += exp.amount
        unit_quantities[exp.unit] = unit_quantities.get(exp.unit, 0.0) + exp.quantity

    qty_parts = [f"{qty:g} {unit}" for unit, qty in unit_quantities.items()]
    total_qty_str = ", ".join(qty_parts) if qty_parts else "0"

    avg_rate_str = "₹ 0.00"
    if len(unit_quantities) == 1:
        unit = list(unit_quantities.keys())[0]
        tot_q = list(unit_quantities.values())[0]
        if tot_q > 0:
            avg_rate_str = f"₹ {(total_spend / tot_q):,.2f} / {unit}"
    elif expenses:
        avg_r = sum(exp.rate for exp in expenses) / len(expenses)
        avg_rate_str = f"₹ {avg_r:,.2f} (avg)"

    # Summary KPI banner table
    kpi_data = [
        ["Total Quantity Used", "Total Spend", "Purchases Count", "Average Rate"],
        [total_qty_str, f"₹ {total_spend:,.2f}", str(len(expenses)), avg_rate_str]
    ]
    kpi_table = Table(kpi_data, colWidths=[135, 135, 135, 145])
    kpi_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#1E3A8A')),
        ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, 0), 9),
        ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('BACKGROUND', (0, 1), (-1, 1), colors.HexColor('#EEF2FF')),
        ('TEXTCOLOR', (0, 1), (-1, 1), colors.HexColor('#1E3A8A')),
        ('FONTNAME', (0, 1), (-1, 1), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 1), (-1, 1), 11),
        ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor('#C7D2FE')),
    ]))
    story.append(kpi_table)
    story.append(Spacer(1, 16))

    # Detailed item purchase table
    data = [["Date", "Category", "Item Description", "Qty", "Unit", "Rate", "Total Spend"]]
    for exp in expenses:
        data.append([
            exp.date.strftime('%d-%m-%Y'),
            exp.category.title(),
            exp.item_name,
            f"{exp.quantity:g}",
            exp.unit,
            f"{exp.rate:,.2f}",
            f"{exp.amount:,.2f}"
        ])

    if not expenses:
        data.append(["-", "No records found in this period", "-", "-", "-", "-", "0.00"])
    else:
        data.append(["", "", "", "", "", "Total:", f"{total_spend:,.2f}"])

    table = Table(data, colWidths=[65, 85, 140, 35, 45, 85, 95])
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#0284C7')),
        ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
        ('ALIGN', (0, 0), (2, -1), 'LEFT'),
        ('ALIGN', (3, 0), (-1, -1), 'RIGHT'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, 0), 9.5),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 6),
        ('TOPPADDING', (0, 0), (-1, 0), 6),
        ('BACKGROUND', (0, 1), (-1, -2), colors.HexColor('#F8FAFC')),
        ('GRID', (0, 0), (-1, -2), 0.5, colors.HexColor('#E2E8F0')),
        ('FONTNAME', (-2, -1), (-1, -1), 'Helvetica-Bold'),
        ('LINEABOVE', (-2, -1), (-1, -1), 1.5, colors.HexColor('#0284C7')),
    ]))
    story.append(table)

    doc.build(story)
    buffer.seek(0)
    clean_item = "".join(c for c in item_query if c.isalnum() or c in (' ', '_', '-')).strip().replace(' ', '_')
    filename = f"Item_Usage_{clean_item}_{start_str}_to_{end_str}.pdf"
    return send_file(buffer, as_attachment=True, download_name=filename, mimetype='application/pdf')

@app.route('/sw.js', methods=['GET'])
def service_worker():
    response = send_file('static/sw.js', mimetype='application/javascript')
    response.headers['Service-Worker-Allowed'] = '/'
    response.headers['Cache-Control'] = 'no-cache'
    return response

@app.route('/manifest.json', methods=['GET'])
def manifest():
    return send_file('static/manifest.json', mimetype='application/manifest+json')

@app.route('/download_apk', methods=['GET'])
def download_apk():
    return send_file(
        'static/ExpenseTracker.apk',
        as_attachment=True,
        download_name='ExpenseTracker.apk',
        mimetype='application/vnd.android.package-archive'
    )

@app.route('/update.apk', methods=['GET'])
@app.route('/download_update_apk', methods=['GET'])
def download_update_apk():
    apk_path = 'static/update.apk' if os.path.exists('static/update.apk') else 'static/ExpenseTracker.apk'
    return send_file(
        apk_path,
        as_attachment=True,
        download_name='update.apk',
        mimetype='application/vnd.android.package-archive'
    )

with app.app_context():
    db.create_all()

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=True)
